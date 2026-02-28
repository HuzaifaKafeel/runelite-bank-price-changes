package com.bankpricechanges;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class WikiPriceClient
{
    private static final String BASE_URL = "https://prices.runescape.wiki/api/v1/osrs";
    private static final String USER_AGENT = "bank-price-changes - RuneLite Plugin";
    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;

    public WikiPriceClient(OkHttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    /**
     * Fetches bulk price change data using two requests:
     * <ul>
     *   <li>{@code /latest} — current buy/sell prices for all items</li>
     *   <li>{@code /5m} or {@code /1h} — rolling-average baseline for the chosen period</li>
     * </ul>
     */
    public Map<Integer, PriceData> fetchPriceChanges(BankPriceChangesConfig.TimePeriod timePeriod)
    {
        Map<Integer, PriceData> result = new HashMap<>();

        try
        {
            JsonObject latestData = fetchJson(BASE_URL + "/latest");
            if (latestData == null || !latestData.has("data"))
            {
                return result;
            }

            String historicalEndpoint = timePeriod == BankPriceChangesConfig.TimePeriod.FIVE_MIN ? "5m" : "1h";
            JsonObject historicalData = fetchJson(BASE_URL + "/" + historicalEndpoint);
            if (historicalData == null || !historicalData.has("data"))
            {
                return result;
            }

            JsonObject latestItems = latestData.getAsJsonObject("data");
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
