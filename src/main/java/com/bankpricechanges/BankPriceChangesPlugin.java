package com.bankpricechanges;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@PluginDescriptor(
    name = "Bank Price Changes",
    description = "Shows price changes directly on bank items",
    tags = {"bank", "price", "ge", "value"}
)
public class BankPriceChangesPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BankPriceChangesOverlay overlay;

    @Inject
    private BankPriceChangesConfig config;

    @Inject
    private OkHttpClient okHttpClient;

    private WikiPriceClient wikiPriceClient;
    private final Map<Integer, PriceData> priceChanges = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;
    private Instant lastFetch = Instant.EPOCH;

    @Provides
    BankPriceChangesConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BankPriceChangesConfig.class);
    }

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);
        wikiPriceClient = new WikiPriceClient(okHttpClient);
        executor = Executors.newSingleThreadScheduledExecutor();
        log.info("Bank Price Changes plugin started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        executor.shutdown();
        priceChanges.clear();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.BANK.getId())
        {
            log.info("Bank container changed, triggering price refresh");
            refreshIfStale();
        }
    }

    private void refreshIfStale()
    {
        if (Duration.between(lastFetch, Instant.now()).toMinutes() < 5)
        {
            return;
        }
        lastFetch = Instant.now();
        executor.submit(() ->
        {
            try
            {
                Map<Integer, PriceData> data = wikiPriceClient.fetchPriceChanges(config.timePeriod());
                priceChanges.clear();
                priceChanges.putAll(data);
                log.info("Fetched price data for {} items", data.size());
            }
            catch (Exception e)
            {
                log.warn("Failed to fetch price changes", e);
            }
        });
    }

    public PriceData getPriceChange(int itemId)
    {
        return priceChanges.get(itemId);
    }
}
