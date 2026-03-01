package com.bankpricechanges;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.OkHttpClient;

import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemMapping;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private Gson gson;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ClientThread clientThread;

    @Inject
    private BankPriceChangesPanel panel;

    private WikiPriceClient wikiPriceClient;
    private final Map<Integer, PriceData> priceChanges = new ConcurrentHashMap<>();
    private final Set<Integer> bankItemIds = ConcurrentHashMap.newKeySet();
    // Bank item IDs extended with ItemMapping component IDs (used for 6h/24h fetch).
    private final Set<Integer> fetchItemIds = ConcurrentHashMap.newKeySet();
    private ExecutorService executor;

    // Per-period price cache (overlay reads from priceChanges, which mirrors the active period)
    private final Map<BankPriceChangesConfig.TimePeriod, Map<Integer, PriceData>>
        priceCache = new ConcurrentHashMap<>();

    // Per-period panel entries — lets us switch periods without touching the client thread
    private final Map<BankPriceChangesConfig.TimePeriod,
        List<BankPriceChangesPanel.PanelItemEntry>>
        entriesByPeriod = new ConcurrentHashMap<>();

    // Per-period last-fetch timestamps
    private final Map<BankPriceChangesConfig.TimePeriod, Instant>
        lastFetchByPeriod = new ConcurrentHashMap<>();

    // Prevents concurrent fetches of the same period
    private final Set<BankPriceChangesConfig.TimePeriod>
        fetchingPeriods = ConcurrentHashMap.newKeySet();

    // Manual refresh cooldown
    private volatile Instant lastManualRefresh = Instant.EPOCH;
    private static final int MANUAL_REFRESH_COOLDOWN_SECONDS = 30;

    private NavigationButton navButton;
    private ItemContainer bankContainer;

    @Provides
    BankPriceChangesConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BankPriceChangesConfig.class);
    }

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);
        wikiPriceClient = new WikiPriceClient(okHttpClient, gson);
        executor = Executors.newFixedThreadPool(4);

        navButton = NavigationButton.builder()
            .tooltip("Bank Price Changes")
            .icon(buildIcon())
            .priority(7)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        log.info("Bank Price Changes plugin started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        clientToolbar.removeNavigation(navButton);
        executor.shutdown();
        priceChanges.clear();
        bankItemIds.clear();
        priceCache.clear();
        entriesByPeriod.clear();
        lastFetchByPeriod.clear();
        fetchingPeriods.clear();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.BANK.getId())
        {
            bankContainer = event.getItemContainer();
            rescanBank();
            log.info("Bank container changed ({} unique items), triggering price refresh", bankItemIds.size());
            refreshAllPeriods();
        }
    }

    void rescanBank()
    {
        if (bankContainer == null)
        {
            return;
        }
        bankItemIds.clear();
        for (Item item : bankContainer.getItems())
        {
            if (item.getId() < 0)
            {
                continue;
            }
            if (!config.includePlaceholders()
                    && itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() != -1)
            {
                continue;
            }
            bankItemIds.add(itemManager.canonicalize(item.getId()));
        }
        fetchItemIds.clear();
        fetchItemIds.addAll(bankItemIds);
        for (Integer bankId : bankItemIds)
        {
            Collection<ItemMapping> mappings = ItemMapping.map(bankId);
            if (mappings != null)
            {
                for (ItemMapping m : mappings)
                {
                    fetchItemIds.add(m.getTradeableItem());
                }
            }
        }
    }

    void refreshAllPeriods()
    {
        for (BankPriceChangesConfig.TimePeriod period : BankPriceChangesConfig.TimePeriod.values())
        {
            final BankPriceChangesConfig.TimePeriod p = period;
            executor.submit(() -> refreshPeriod(p));
        }
    }

    private void refreshPeriod(BankPriceChangesConfig.TimePeriod period)
    {
        // Staleness guard
        Instant last = lastFetchByPeriod.getOrDefault(period, Instant.EPOCH);
        if (Duration.between(last, Instant.now()).toMinutes() < 5)
        {
            return;
        }
        // Concurrency guard — skip if a fetch is already in-flight for this period
        if (!fetchingPeriods.add(period))
        {
            return;
        }
        lastFetchByPeriod.put(period, Instant.now());
        try
        {
            Map<Integer, PriceData> data = wikiPriceClient.fetchPriceChanges(period, fetchItemIds);
            priceCache.put(period, data);
            log.info("Fetched price data for {} items ({})", data.size(), period);

            clientThread.invokeLater(() ->
            {
                List<BankPriceChangesPanel.PanelItemEntry> entries = new ArrayList<>();
                for (Integer bankId : bankItemIds)
                {
                    PriceData priceData = data.get(bankId);
                    if (priceData == null)
                    {
                        Collection<ItemMapping> mappings = ItemMapping.map(bankId);
                        if (mappings == null)
                        {
                            continue;
                        }
                        long currentTotal = 0;
                        long previousTotal = 0;
                        boolean allFound = true;
                        for (ItemMapping mapping : mappings)
                        {
                            PriceData component = data.get(mapping.getTradeableItem());
                            if (component == null)
                            {
                                allFound = false;
                                break;
                            }
                            currentTotal  += (long) component.getCurrentPrice()  * mapping.getQuantity();
                            previousTotal += (long) component.getPreviousPrice() * mapping.getQuantity();
                        }
                        if (!allFound || previousTotal == 0)
                        {
                            continue;
                        }
                        priceData = PriceData.of((int) currentTotal, (int) previousTotal);
                        data.put(bankId, priceData); // keep cache consistent
                    }
                    String itemName = itemManager.getItemComposition(bankId).getName();
                    entries.add(new BankPriceChangesPanel.PanelItemEntry(bankId, itemName, priceData));
                }
                entriesByPeriod.put(period, entries);

                // Only update the live display if this is the currently selected period
                if (period == config.timePeriod())
                {
                    priceChanges.clear();
                    priceChanges.putAll(data);
                    SwingUtilities.invokeLater(() -> panel.updateData(entries));
                }
            });
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch price changes for period {}", period, e);
        }
        finally
        {
            fetchingPeriods.remove(period);
        }
    }

    public void refreshNow()
    {
        Instant now = Instant.now();
        if (Duration.between(lastManualRefresh, now).getSeconds() < MANUAL_REFRESH_COOLDOWN_SECONDS)
        {
            log.debug("Manual refresh ignored — cooldown active");
            return;
        }
        lastManualRefresh = now;
        lastFetchByPeriod.clear();
        refreshAllPeriods();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"bankpricechanges".equals(event.getGroup()))
        {
            return;
        }
        SwingUtilities.invokeLater(() -> panel.syncFromConfig());
        switch (event.getKey())
        {
            case "timePeriod":
                BankPriceChangesConfig.TimePeriod newPeriod = config.timePeriod();
                Map<Integer, PriceData> cached = priceCache.get(newPeriod);
                List<BankPriceChangesPanel.PanelItemEntry> cachedEntries = entriesByPeriod.get(newPeriod);
                Instant lastForPeriod = lastFetchByPeriod.get(newPeriod);
                boolean fresh = lastForPeriod != null
                    && Duration.between(lastForPeriod, Instant.now()).toMinutes() < 5;

                if (cached != null && cachedEntries != null && fresh)
                {
                    // Instant switch — data already in memory
                    priceChanges.clear();
                    priceChanges.putAll(cached);
                    SwingUtilities.invokeLater(() -> panel.updateData(cachedEntries));
                }
                else
                {
                    // Cache miss — fetch only this period (others continue in background)
                    executor.submit(() -> refreshPeriod(newPeriod));
                }
                break;
            case "includePlaceholders":
                clientThread.invokeLater(() ->
                {
                    rescanBank();
                    refreshAllPeriods();
                });
                break;
        }
    }

    public PriceData getPriceChange(int itemId)
    {
        return priceChanges.get(itemId);
    }

    private static BufferedImage buildIcon()
    {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw a simple gold bar-chart icon
        g.setColor(new Color(200, 160, 0)); // gold
        g.fillRect(1, 10, 3, 5);            // short bar (left)
        g.fillRect(6, 6, 3, 9);             // medium bar (middle)
        g.fillRect(11, 2, 3, 13);           // tall bar (right)

        // Highlight
        g.setColor(new Color(255, 220, 80));
        g.fillRect(1, 10, 1, 5);
        g.fillRect(6, 6, 1, 9);
        g.fillRect(11, 2, 1, 13);

        g.dispose();
        return image;
    }
}
