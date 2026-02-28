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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class WikiPriceClient
{
    private static final String BASE_URL = "https://prices.runescape.wiki/api/v1/osrs";
    private static final String USER_AGENT = "bank-price-changes - RuneLite Plugin";
    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;        // bulk endpoints
    private final OkHttpClient timeseriesClient;  // per-item timeseries

    public WikiPriceClient(OkHttpClient httpClient)
    {
        this.httpClient = httpClient;
        Dispatcher d = new Dispatcher();
        d.setMaxRequestsPerHost(20);
        this.timeseriesClient = httpClient.newBuilder().dispatcher(d).build();
    }

    /**
     * Fetches price change data for all items.
     * <ul>
     *   <li>{@code FIVE_MIN} / {@code ONE_HOUR} — bulk {@code /5m} or {@code /1h} endpoints</li>
     *   <li>{@code SIX_HOURS} / {@code TWENTY_FOUR_HOURS} — parallel per-item timeseries requests</li>
     * </ul>
     */
    public Map<Integer, PriceData> fetchPriceChanges(
        BankPriceChangesConfig.TimePeriod timePeriod, Set<Integer> bankItemIds)
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

            if (timePeriod == BankPriceChangesConfig.TimePeriod.SIX_HOURS)
            {
                return fetchTimeseriesForItems(latestItems, bankItemIds, "6h");
            }
            if (timePeriod == BankPriceChangesConfig.TimePeriod.TWENTY_FOUR_HOURS)
            {
                return fetchTimeseriesForItems(latestItems, bankItemIds, "24h");
            }

            // Bulk endpoint for 5m / 1h
            String historicalEndpoint = timePeriod == BankPriceChangesConfig.TimePeriod.FIVE_MIN ? "5m" : "1h";
            JsonObject historicalData = fetchJson(BASE_URL + "/" + historicalEndpoint);
            if (historicalData == null || !historicalData.has("data"))
            {
                return result;
            }

            JsonObject historicalItems = historicalData.getAsJsonObject("data");

            for (Map.Entry<String, JsonElement> entry : latestItems.entrySet())
            {
                String itemIdStr = entry.getKey();
                int itemId;
                try
                {
                    itemId = Integer.parseInt(itemIdStr);
                }
                catch (NumberFormatException e)
                {
                    continue;
                }

                int currentPrice = getAveragePrice(entry.getValue().getAsJsonObject());
                if (currentPrice <= 0)
                {
                    continue;
                }

                JsonElement historicalElement = historicalItems.get(itemIdStr);
                if (historicalElement == null || historicalElement.isJsonNull())
                {
                    continue;
                }

                int previousPrice = getAveragePrice(historicalElement.getAsJsonObject());
                if (previousPrice <= 0)
                {
                    continue;
                }

                result.put(itemId, PriceData.of(currentPrice, previousPrice));
            }
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch price data from wiki API", e);
        }

        return result;
    }

    /**
     * Fires one async timeseries request per bank item using the dedicated high-concurrency client.
     * Takes only the last data point from each response (most recently completed interval).
     */
    private Map<Integer, PriceData> fetchTimeseriesForItems(
        JsonObject latestItems, Set<Integer> itemIds, String timestep)
    {
        Map<Integer, PriceData> result = new ConcurrentHashMap<>();

        if (itemIds.isEmpty())
        {
            return result;
        }

        // Collect items that actually have a current price
        Map<Integer, Integer> currentPrices = new HashMap<>();
        for (Integer itemId : itemIds)
        {
            String itemIdStr = String.valueOf(itemId);
            JsonElement el = latestItems.get(itemIdStr);
            if (el == null || el.isJsonNull())
            {
                continue;
            }
            int currentPrice = getAveragePrice(el.getAsJsonObject());
            if (currentPrice > 0)
            {
                currentPrices.put(itemId, currentPrice);
            }
        }

        if (currentPrices.isEmpty())
        {
            return result;
        }

        CountDownLatch latch = new CountDownLatch(currentPrices.size());

        for (Map.Entry<Integer, Integer> entry : currentPrices.entrySet())
        {
            int itemId = entry.getKey();
            int currentPrice = entry.getValue();

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
                    log.warn("Timeseries request failed for item {}", itemId, e);
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException
                {
                    try
                    {
                        if (!response.isSuccessful() || response.body() == null)
                        {
                            log.warn("Timeseries request unsuccessful for item {}: {}", itemId, response.code());
                            return;
                        }

                        JsonObject json = GSON.fromJson(response.body().charStream(), JsonObject.class);
                        if (json == null || !json.has("data"))
                        {
                            return;
                        }

                        JsonArray dataPoints = json.getAsJsonArray("data");
                        if (dataPoints == null || dataPoints.size() == 0)
                        {
                            return;
                        }

                        JsonObject lastPoint = dataPoints.get(dataPoints.size() - 1).getAsJsonObject();
                        int previousPrice = getAveragePrice(lastPoint);
                        if (previousPrice <= 0)
                        {
                            return;
                        }

                        result.put(itemId, PriceData.of(currentPrice, previousPrice));
                    }
                    finally
                    {
                        response.close();
                        latch.countDown();
                    }
                }
            });
        }

        try
        {
            latch.await(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            log.warn("Timeseries fetch interrupted");
        }

        return result;
    }

    private int getAveragePrice(JsonObject priceObj)
    {
        Integer high = getIntOrNull(priceObj, "high");
        Integer low = getIntOrNull(priceObj, "low");

        // /latest uses "high"/"low"; /5m and /1h use "avgHighPrice"/"avgLowPrice"
        if (high == null)
        {
            high = getIntOrNull(priceObj, "avgHighPrice");
        }
        if (low == null)
        {
            low = getIntOrNull(priceObj, "avgLowPrice");
        }

        if (high != null && low != null)
        {
            return (high + low) / 2;
        }
        if (high != null)
        {
            return high;
        }
        if (low != null)
        {
            return low;
        }
        return 0;
    }

    private Integer getIntOrNull(JsonObject obj, String key)
    {
        if (!obj.has(key) || obj.get(key).isJsonNull())
        {
            return null;
        }
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

            return GSON.fromJson(response.body().charStream(), JsonObject.class);
        }
    }
}
