package com.bankpricechanges;

import lombok.Value;

@Value
public class PriceData
{
    int currentPrice;
    int previousPrice;
    int change;
    double changePct;

    public static PriceData of(int currentPrice, int previousPrice)
    {
        int change = currentPrice - previousPrice;
        double changePct = previousPrice != 0
            ? (change / (double) previousPrice) * 100.0
            : 0.0;
        return new PriceData(currentPrice, previousPrice, change, changePct);
    }
}
