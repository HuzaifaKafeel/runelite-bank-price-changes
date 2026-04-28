package com.bankpricechanges;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bankpricechanges")
public interface BankPriceChangesConfig extends Config
{
    enum PriceMode
    {
        HIGH,
        LOW
    }

    enum TimePeriod
    {
        FIVE_MIN("5m"),
        ONE_HOUR("1h"),
        SIX_HOURS("6h"),
        TWENTY_FOUR_HOURS("24h"),
        ONE_WEEK("1w"),
        ONE_MONTH("1mo"),
        ONE_YEAR("1yr");

        private final String label;

        TimePeriod(String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    enum PanelItemCount
    {
        FIVE(5),
        TEN(10);

        final int count;

        PanelItemCount(int count)
        {
            this.count = count;
        }

        @Override
        public String toString()
        {
            return String.valueOf(count);
        }
    }

    // ── Hidden panel state (persisted, not shown in config panel UI) ─────────

    @ConfigItem(keyName = "showByPercent",      name = "", description = "", hidden = true)
    default boolean showByPercent() { return false; }

    @ConfigItem(keyName = "priceMode",          name = "", description = "", hidden = true)
    default PriceMode priceMode() { return PriceMode.LOW; }

    @ConfigItem(keyName = "minThreshold",       name = "", description = "", hidden = true)
    default double minThreshold() { return 0.0; }

    @ConfigItem(keyName = "timePeriod",         name = "", description = "", hidden = true)
    default TimePeriod timePeriod() { return TimePeriod.TWENTY_FOUR_HOURS; }

    @ConfigItem(keyName = "panelItemCount",     name = "", description = "", hidden = true)
    default PanelItemCount panelItemCount() { return PanelItemCount.FIVE; }

    @ConfigItem(keyName = "includePlaceholders",name = "", description = "", hidden = true)
    default boolean includePlaceholders() { return true; }

    @ConfigItem(keyName = "minGpThreshold",     name = "", description = "", hidden = true)
    default int minGpThreshold() { return 0; }

    // ── Visible config panel setting ──────────────────────────────────────────

    @ConfigItem(
        keyName = "showBankOverlay",
        name = "Show Bank Overlay",
        description = "Show price change labels on bank items"
    )
    default boolean showBankOverlay() { return true; }
}
