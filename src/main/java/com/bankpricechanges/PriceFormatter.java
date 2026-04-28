package com.bankpricechanges;

public class PriceFormatter
{
    public static String formatGp(int amount)
    {
        int abs = Math.abs(amount);
        if (abs >= 1_000_000_000) return String.format("%.1fb", amount / 1_000_000_000.0);
        if (abs >= 1_000_000)     return String.format("%.2fm", amount / 1_000_000.0);
        if (abs >= 100_000)       return String.format("%dk",   amount / 1_000);
        if (abs >= 1_000)         return String.format("%.1fk", amount / 1_000.0);
        return String.valueOf(amount);
    }
}
