package com.bankpricechanges;

import lombok.Value;

@Value
public class PriceData
{
    int currentHigh;
    int currentLow;
    int previousHigh;
    int previousLow;

    public int getChange(BankPriceChangesConfig.PriceMode mode)
    {
        int cur  = mode == BankPriceChangesConfig.PriceMode.HIGH ? currentHigh  : currentLow;
        int prev = mode == BankPriceChangesConfig.PriceMode.HIGH ? previousHigh : previousLow;
        return cur - prev;
    }

    public double getChangePct(BankPriceChangesConfig.PriceMode mode)
    {
        int prev = mode == BankPriceChangesConfig.PriceMode.HIGH ? previousHigh : previousLow;
        return prev != 0 ? (getChange(mode) / (double) prev) * 100.0 : 0.0;
    }

    public static PriceData of(int currentHigh, int currentLow, int previousHigh, int previousLow)
    {
        return new PriceData(currentHigh, currentLow, previousHigh, previousLow);
    }
}
