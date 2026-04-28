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
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@PluginDescriptor(
    name = "Bank Price Changes",
    description = "Shows price changes directly on bank items",
    tags = {"bank", "price", "ge", "value"}
)
public class BankPriceChangesPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ItemManager itemManager;
    @Inject private OverlayManager overlayManager;
    @Inject private BankPriceChangesOverlay overlay;
    @Inject private BankPriceChangesConfig config;
    @Inject private Gson gson;
    @Inject private OkHttpClient okHttpClient;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ClientThread clientThread;
    @Inject private BankPriceChangesPanel panel;

    private WikiPriceClient wikiPriceClient;
    private final Map<Integer, PriceData> priceChanges = new ConcurrentHashMap<>();
    private final Set<Integer> bankItemIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> fetchItemIds = ConcurrentHashMap.newKeySet();
    private ExecutorService executor;

    // Per-period price cache
    private final Map<BankPriceChangesConfig.TimePeriod, Map<Integer, PriceData>>
        priceCache = new ConcurrentHashMap<>();

    // Per-period panel entries
    private final Map<BankPriceChangesConfig.TimePeriod, List<BankPriceChangesPanel.PanelItemEntry>>
        entriesByPeriod = new ConcurrentHashMap<>();

    // Last-fetch timestamps — keyed by period for bulk, and by a sentinel for the two timeseries groups
    private final Map<BankPriceChangesConfig.TimePeriod, Instant>
        lastFetchByPeriod = new ConcurrentHashMap<>();

    // In-flight guards
    private final AtomicBoolean fetchingTimeseries = new AtomicBoolean(false);
    private final Set<BankPriceChangesConfig.TimePeriod>
        fetchingBulk = ConcurrentHashMap.newKeySet();

    private volatile Instant lastManualRefresh = Instant.EPOCH;
    private static final int MANUAL_REFRESH_COOLDOWN_SECONDS = 30;
    private static final int STALE_MINUTES = 5;

    // Sentinel period used to track staleness of the shared 6h timeseries fetch
    private static final BankPriceChangesConfig.TimePeriod SENTINEL_6H =
        BankPriceChangesConfig.TimePeriod.SIX_HOURS;
    // Sentinel period used to track staleness of the shared daily timeseries fetch (24h/1w/1m/1y)
    private static final BankPriceChangesConfig.TimePeriod SENTINEL_DAILY =
        BankPriceChangesConfig.TimePeriod.TWENTY_FOUR_HOURS;

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
        if (bankContainer == null) return;
        bankItemIds.clear();
        for (Item item : bankContainer.getItems())
        {
            if (item.getId() < 0) continue;
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
        executor.submit(this::refreshBulkPeriod5m);
        executor.submit(this::refreshBulkPeriod1h);
        executor.submit(this::refreshSixHourPeriod);
        executor.submit(this::refreshDailyPeriods);
    }

    private void refreshBulkPeriod5m()
    {
        refreshBulkPeriod(BankPriceChangesConfig.TimePeriod.FIVE_MIN);
    }

    private void refreshBulkPeriod1h()
    {
        refreshBulkPeriod(BankPriceChangesConfig.TimePeriod.ONE_HOUR);
    }

    private void refreshBulkPeriod(BankPriceChangesConfig.TimePeriod period)
    {
        Instant last = lastFetchByPeriod.getOrDefault(period, Instant.EPOCH);
        if (Duration.between(last, Instant.now()).toMinutes() < STALE_MINUTES) return;
        if (!fetchingBulk.add(period)) return;
        lastFetchByPeriod.put(period, Instant.now());
        try
        {
            Map<Integer, PriceData> data = wikiPriceClient.fetchBulkPeriod(period);
            priceCache.put(period, data);
            log.info("Fetched bulk price data for {} items ({})", data.size(), period);
            pushToPanel(period, data);
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch bulk period {}", period, e);
        }
        finally
        {
            fetchingBulk.remove(period);
        }
    }

    private void refreshSixHourPeriod()
    {
        Instant last = lastFetchByPeriod.getOrDefault(SENTINEL_6H, Instant.EPOCH);
        if (Duration.between(last, Instant.now()).toMinutes() < STALE_MINUTES) return;
        if (!fetchingTimeseries.compareAndSet(false, true)) return;
        lastFetchByPeriod.put(SENTINEL_6H, Instant.now());
        try
        {
            Set<Integer> ids = Set.copyOf(fetchItemIds);
            Map<Integer, PriceData> data = wikiPriceClient.fetchSixHourPeriod(ids);
            priceCache.put(BankPriceChangesConfig.TimePeriod.SIX_HOURS, data);
            log.info("Fetched 6h timeseries for {} items", data.size());
            pushToPanel(BankPriceChangesConfig.TimePeriod.SIX_HOURS, data);
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch 6h timeseries", e);
        }
        finally
        {
            fetchingTimeseries.set(false);
        }
    }

    private void refreshDailyPeriods()
    {
        Instant last = lastFetchByPeriod.getOrDefault(SENTINEL_DAILY, Instant.EPOCH);
        if (Duration.between(last, Instant.now()).toMinutes() < STALE_MINUTES) return;
        // Use a separate atomic for daily to allow 6h and daily to run concurrently
        // Simple staleness + period-keyed guard is enough here since executor is multi-threaded
        lastFetchByPeriod.put(SENTINEL_DAILY, Instant.now());
        try
        {
            Set<Integer> ids = Set.copyOf(fetchItemIds);
            Map<BankPriceChangesConfig.TimePeriod, Map<Integer, PriceData>> allData =
                wikiPriceClient.fetchDailyPeriods(ids);

            for (Map.Entry<BankPriceChangesConfig.TimePeriod, Map<Integer, PriceData>> entry
                : allData.entrySet())
            {
                priceCache.put(entry.getKey(), entry.getValue());
                log.info("Fetched daily timeseries ({}) for {} items",
                    entry.getKey(), entry.getValue().size());
                pushToPanel(entry.getKey(), entry.getValue());
            }
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch daily timeseries periods", e);
        }
    }

    /** Resolves component-item names on the client thread, then pushes entries to the panel. */
    private void pushToPanel(BankPriceChangesConfig.TimePeriod period, Map<Integer, PriceData> data)
    {
        clientThread.invokeLater(() ->
        {
            List<BankPriceChangesPanel.PanelItemEntry> entries = new ArrayList<>();
            for (Integer bankId : bankItemIds)
            {
                PriceData priceData = data.get(bankId);
                if (priceData == null)
                {
                    Collection<ItemMapping> mappings = ItemMapping.map(bankId);
                    if (mappings == null) continue;

                    long curHighTotal = 0, curLowTotal = 0;
                    long prevHighTotal = 0, prevLowTotal = 0;
                    boolean allFound = true;

                    for (ItemMapping mapping : mappings)
                    {
                        PriceData component = data.get(mapping.getTradeableItem());
                        if (component == null) { allFound = false; break; }
                        curHighTotal  += (long) component.getCurrentHigh()  * mapping.getQuantity();
                        curLowTotal   += (long) component.getCurrentLow()   * mapping.getQuantity();
                        prevHighTotal += (long) component.getPreviousHigh() * mapping.getQuantity();
                        prevLowTotal  += (long) component.getPreviousLow()  * mapping.getQuantity();
                    }
                    if (!allFound || (prevHighTotal == 0 && prevLowTotal == 0)) continue;
                    priceData = PriceData.of(
                        (int) curHighTotal, (int) curLowTotal,
                        (int) prevHighTotal, (int) prevLowTotal
                    );
                    data.put(bankId, priceData);
                }
                String itemName = itemManager.getItemComposition(bankId).getName();
                entries.add(new BankPriceChangesPanel.PanelItemEntry(bankId, itemName, priceData));
            }
            entriesByPeriod.put(period, entries);

            if (period == config.timePeriod())
            {
                priceChanges.clear();
                priceChanges.putAll(data);
                SwingUtilities.invokeLater(() -> panel.updateData(entries));
            }
        });
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
        if (!"bankpricechanges".equals(event.getGroup())) return;

        SwingUtilities.invokeLater(() -> panel.syncFromConfig());

        switch (event.getKey())
        {
            case "timePeriod":
                BankPriceChangesConfig.TimePeriod newPeriod = config.timePeriod();
                Map<Integer, PriceData> cached = priceCache.get(newPeriod);
                List<BankPriceChangesPanel.PanelItemEntry> cachedEntries = entriesByPeriod.get(newPeriod);
                Instant lastForPeriod = lastFetchByPeriod.get(getStalenessKey(newPeriod));
                boolean fresh = lastForPeriod != null
                    && Duration.between(lastForPeriod, Instant.now()).toMinutes() < STALE_MINUTES;

                if (cached != null && cachedEntries != null && fresh)
                {
                    priceChanges.clear();
                    priceChanges.putAll(cached);
                    SwingUtilities.invokeLater(() -> panel.updateData(cachedEntries));
                }
                else
                {
                    executor.submit(() -> fetchPeriod(newPeriod));
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

    /** Returns the staleness-tracking key for a given period. */
    private BankPriceChangesConfig.TimePeriod getStalenessKey(BankPriceChangesConfig.TimePeriod period)
    {
        switch (period)
        {
            case FIVE_MIN:      return BankPriceChangesConfig.TimePeriod.FIVE_MIN;
            case ONE_HOUR:      return BankPriceChangesConfig.TimePeriod.ONE_HOUR;
            case SIX_HOURS:     return SENTINEL_6H;
            default:            return SENTINEL_DAILY; // 24h, 1w, 1m, 1y
        }
    }

    /** Fetches only the group containing the given period (used on cache miss after period switch). */
    private void fetchPeriod(BankPriceChangesConfig.TimePeriod period)
    {
        switch (period)
        {
            case FIVE_MIN:  refreshBulkPeriod(BankPriceChangesConfig.TimePeriod.FIVE_MIN);  break;
            case ONE_HOUR:  refreshBulkPeriod(BankPriceChangesConfig.TimePeriod.ONE_HOUR);  break;
            case SIX_HOURS: refreshSixHourPeriod(); break;
            default:        refreshDailyPeriods();  break;
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

        g.setColor(new Color(200, 160, 0));
        g.fillRect(1, 10, 3, 5);
        g.fillRect(6, 6, 3, 9);
        g.fillRect(11, 2, 3, 13);

        g.setColor(new Color(255, 220, 80));
        g.fillRect(1, 10, 1, 5);
        g.fillRect(6, 6, 1, 9);
        g.fillRect(11, 2, 1, 13);

        g.dispose();
        return image;
    }
}
