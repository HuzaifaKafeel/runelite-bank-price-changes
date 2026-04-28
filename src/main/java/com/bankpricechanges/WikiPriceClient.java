package com.bankpricechanges;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WikiPriceClient
{
    private static final String BASE_URL = "https://prices.runescape.wiki/api/v1/osrs";
    private static final String USER_AGENT = "bank-price-changes - RuneLite Plugin";

    // Target lookback durations in seconds for each timeseries-based period
    private static final Map<BankPriceChangesConfig.TimePeriod, Long> DAILY_PERIOD_SECONDS;
    static
    {
        DAILY_PERIOD_SECONDS = new EnumMap<>(BankPriceChangesConfig.TimePeriod.class);
        DAILY_PERIOD_SECONDS.put(BankPriceChangesConfig.TimePeriod.TWENTY_FOUR_HOURS, 24L * 3600);
        DAILY_PERIOD_SECONDS.put(BankPriceChangesConfig.TimePeriod.ONE_WEEK,          7L  * 24 * 3600);
        DAILY_PERIOD_SECONDS.put(BankPriceChangesConfig.TimePeriod.ONE_MONTH,         30L * 24 * 3600);
        DAILY_PERIOD_SECONDS.put(BankPriceChangesConfig.TimePeriod.ONE_YEAR,          365L * 24 * 3600);
    }

    private final Gson gson;
    private final OkHttpClient httpClient;
    private final OkHttpClient timeseriesClient;

    public WikiPriceClient(OkHttpClient httpClient, Gson gson)
    {
        this.gson = gson;
        this.httpClient = httpClient;
        Dispatcher d = new Dispatcher();
        d.setMaxRequestsPerHost(20);
        this.timeseriesClient = httpClient.newBuilder().dispatcher(d).build();
    }

    /**
     * Fetches price data for the 5m or 1h bulk endpoint.
     * Returns a map of itemId → PriceData for all tradeable items.
     */
    public Map<Integer, PriceData> fetchBulkPeriod(BankPriceChangesConfig.TimePeriod period)
    {
        Map<Integer, PriceData> result = new HashMap<>();
        try
        {
            JsonObject latestData = fetchJson(BASE_URL + "/latest");
            if (latestData == null || !latestData.has("data"))
            {
                return result;
            }
            JsonObject latestItems = latestData.getAsJsonObject("data");

            String endpoint = period == BankPriceChangesConfig.TimePeriod.FIVE_MIN ? "5m" : "1h";
            JsonObject historicalData = fetchJson(BASE_URL + "/" + endpoint);
            if (historicalData == null || !historicalData.has("data"))
            {
                return result;
            }
            JsonObject historicalItems = historicalData.getAsJsonObject("data");

            for (Map.Entry<String, JsonElement> entry : latestItems.entrySet())
            {
                String idStr = entry.getKey();
                int itemId;
                try { itemId = Integer.parseInt(idStr); }
                catch (NumberFormatException e) { continue; }

                JsonObject currentObj = entry.getValue().getAsJsonObject();
                Integer curHigh = getHigh(currentObj);
                Integer curLow  = getLow(currentObj);
                if (curHigh == null && curLow == null) continue;

                JsonElement histEl = historicalItems.get(idStr);
                if (histEl == null || histEl.isJsonNull()) continue;

                JsonObject histObj = histEl.getAsJsonObject();
                Integer prevHigh = getHigh(histObj);
                Integer prevLow  = getLow(histObj);
                if (prevHigh == null && prevLow == null) continue;

                result.put(itemId, PriceData.of(
                    curHigh  != null ? curHigh  : 0,
                    curLow   != null ? curLow   : 0,
                    prevHigh != null ? prevHigh : 0,
                    prevLow  != null ? prevLow  : 0
                ));
            }
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch bulk price data ({})", period, e);
        }
        return result;
    }

    /**
     * Fires one async timeseries request per item using timestep=6h.
     * Finds the data point whose timestamp is closest to now-6h.
     */
    public Map<Integer, PriceData> fetchSixHourPeriod(Set<Integer> itemIds)
    {
        Map<Integer, PriceData> result = new ConcurrentHashMap<>();
        try
        {
            JsonObject latestData = fetchJson(BASE_URL + "/latest");
            if (latestData == null || !latestData.has("data")) return result;
            JsonObject latestItems = latestData.getAsJsonObject("data");

            Map<Integer, int[]> currentPrices = extractCurrentPrices(latestItems, itemIds);
            if (currentPrices.isEmpty()) return result;

            long targetEpoch = Instant.now().minusSeconds(6L * 3600).getEpochSecond();
            fireTimeseriesRequests(currentPrices, "6h", targetEpoch, result);
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch 6h timeseries data", e);
        }
        return result;
    }

    /**
     * Fires one async timeseries request per item using timestep=24h.
     * From the 365-point response, extracts the closest data point for each of
     * TWENTY_FOUR_HOURS, ONE_WEEK, ONE_MONTH, and ONE_YEAR simultaneously.
     */
    public Map<BankPriceChangesConfig.TimePeriod, Map<Integer, PriceData>> fetchDailyPeriods(
        Set<Integer> itemIds)
    {
        Map<BankPriceChangesConfig.TimePeriod, Map<Integer, PriceData>> results = new EnumMap<>(
            BankPriceChangesConfig.TimePeriod.class);
        for (BankPriceChangesConfig.TimePeriod p : DAILY_PERIOD_SECONDS.keySet())
        {
            results.put(p, new ConcurrentHashMap<>());
        }

        try
        {
            JsonObject latestData = fetchJson(BASE_URL + "/latest");
            if (latestData == null || !latestData.has("data")) return results;
            JsonObject latestItems = latestData.getAsJsonObject("data");

            Map<Integer, int[]> currentPrices = extractCurrentPrices(latestItems, itemIds);
            if (currentPrices.isEmpty()) return results;

            long now = Instant.now().getEpochSecond();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Map.Entry<Integer, int[]> entry : currentPrices.entrySet())
            {
                int itemId = entry.getKey();
                int[] curPrices = entry.getValue(); // [high, low]

                CompletableFuture<Void> future = new CompletableFuture<>();
                futures.add(future);

                String url = BASE_URL + "/timeseries?timestep=24h&id=" + itemId;
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();

                timeseriesClient.newCall(request).enqueue(new Callback()
                {
                    @Override
                    public void onFailure(Call call, IOException e)
                    {
                        log.warn("Daily timeseries request failed for item {}", itemId, e);
                        future.complete(null);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException
                    {
                        try
                        {
                            if (!response.isSuccessful() || response.body() == null)
                            {
                                log.warn("Daily timeseries unsuccessful for item {}: {}", itemId, response.code());
                                return;
                            }
                            JsonObject json = gson.fromJson(response.body().charStream(), JsonObject.class);
                            if (json == null || !json.has("data")) return;

                            JsonArray dataPoints = json.getAsJsonArray("data");
                            if (dataPoints == null || dataPoints.size() == 0) return;

                            for (Map.Entry<BankPriceChangesConfig.TimePeriod, Long> pe
                                : DAILY_PERIOD_SECONDS.entrySet())
                            {
                                long targetEpoch = now - pe.getValue();
                                JsonObject best = findClosestPoint(dataPoints, targetEpoch);
                                if (best == null) continue;

                                Integer prevHigh = getHigh(best);
                                Integer prevLow  = getLow(best);
                                if (prevHigh == null && prevLow == null) continue;

                                results.get(pe.getKey()).put(itemId, PriceData.of(
                                    curPrices[0],
                                    curPrices[1],
                                    prevHigh != null ? prevHigh : 0,
                                    prevLow  != null ? prevLow  : 0
                                ));
                            }
                        }
                        finally
                        {
                            response.close();
                            future.complete(null);
                        }
                    }
                });
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch daily timeseries data", e);
        }
        return results;
    }

    // ── Helpers ───────────────────────────────────────────────

    private Map<Integer, int[]> extractCurrentPrices(JsonObject latestItems, Set<Integer> itemIds)
    {
        Map<Integer, int[]> currentPrices = new HashMap<>();
        for (Integer itemId : itemIds)
        {
            JsonElement el = latestItems.get(String.valueOf(itemId));
            if (el == null || el.isJsonNull()) continue;
            JsonObject obj = el.getAsJsonObject();
            Integer high = getHigh(obj);
            Integer low  = getLow(obj);
            if (high == null && low == null) continue;
            currentPrices.put(itemId, new int[]{
                high != null ? high : 0,
                low  != null ? low  : 0
            });
        }
        return currentPrices;
    }

    private void fireTimeseriesRequests(
        Map<Integer, int[]> currentPrices,
        String timestep,
        long targetEpoch,
        Map<Integer, PriceData> result)
    {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<Integer, int[]> entry : currentPrices.entrySet())
        {
            int itemId = entry.getKey();
            int[] curPrices = entry.getValue();

            CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);

            String url = BASE_URL + "/timeseries?timestep=" + timestep + "&id=" + itemId;
            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

            timeseriesClient.newCall(request).enqueue(new Callback()
            {
                @Override
                public void onFailure(Call call, IOException e)
                {
                    log.warn("Timeseries ({}) request failed for item {}", timestep, itemId, e);
                    future.complete(null);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException
                {
                    try
                    {
                        if (!response.isSuccessful() || response.body() == null)
                        {
                            log.warn("Timeseries ({}) unsuccessful for item {}: {}",
                                timestep, itemId, response.code());
                            return;
                        }
                        JsonObject json = gson.fromJson(response.body().charStream(), JsonObject.class);
                        if (json == null || !json.has("data")) return;

                        JsonArray dataPoints = json.getAsJsonArray("data");
                        if (dataPoints == null || dataPoints.size() == 0) return;

                        JsonObject best = findClosestPoint(dataPoints, targetEpoch);
                        if (best == null) return;

                        Integer prevHigh = getHigh(best);
                        Integer prevLow  = getLow(best);
                        if (prevHigh == null && prevLow == null) return;

                        result.put(itemId, PriceData.of(
                            curPrices[0],
                            curPrices[1],
                            prevHigh != null ? prevHigh : 0,
                            prevLow  != null ? prevLow  : 0
                        ));
                    }
                    finally
                    {
                        response.close();
                        future.complete(null);
                    }
                }
            });
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private JsonObject findClosestPoint(JsonArray dataPoints, long targetEpoch)
    {
        JsonObject best = null;
        long bestDiff = Long.MAX_VALUE;
        for (JsonElement el : dataPoints)
        {
            JsonObject point = el.getAsJsonObject();
            if (!point.has("timestamp") || point.get("timestamp").isJsonNull()) continue;
            long ts = point.get("timestamp").getAsLong();
            long diff = Math.abs(ts - targetEpoch);
            if (diff < bestDiff)
            {
                bestDiff = diff;
                best = point;
            }
        }
        return best;
    }

    private Integer getHigh(JsonObject obj)
    {
        Integer v = getIntOrNull(obj, "high");
        return v != null ? v : getIntOrNull(obj, "avgHighPrice");
    }

    private Integer getLow(JsonObject obj)
    {
        Integer v = getIntOrNull(obj, "low");
        return v != null ? v : getIntOrNull(obj, "avgLowPrice");
    }

    private Integer getIntOrNull(JsonObject obj, String key)
    {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsInt();
    }

    private JsonObject fetchJson(String url) throws IOException
    {
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log.warn("Wiki API request failed: {} {}", response.code(), url);
                return null;
            }
            return gson.fromJson(response.body().charStream(), JsonObject.class);
        }
    }
}
