package com.bankpricechanges;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bankpricechanges")
public interface BankPriceChangesConfig extends Config
{
    enum DisplayMode
    {
        PERCENTAGE,
        GP_AMOUNT,
        BOTH
    }

    enum TimePeriod
    {
        ONE_HOUR("1 Hour"),
        SIX_HOURS("6 Hours"),
        TWENTY_FOUR_HOURS("24 Hours");

        private final String name;

        TimePeriod(String name)
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(
        keyName = "displayMode",
        name = "Display Mode",
        description = "Show price change as percentage, GP amount, or both"
    )
    default DisplayMode displayMode()
    {
        return DisplayMode.PERCENTAGE;
    }

    @ConfigItem(
        keyName = "minThreshold",
        name = "Minimum Change %",
        description = "Only show overlay on items with at least this % change"
    )
    default double minThreshold()
    {
        return 1.0;
    }

    @ConfigItem(
        keyName = "timePeriod",
        name = "Time Period",
        description = "Lookback period for price comparison"
    )
    default TimePeriod timePeriod()
    {
        return TimePeriod.TWENTY_FOUR_HOURS;
    }
}
