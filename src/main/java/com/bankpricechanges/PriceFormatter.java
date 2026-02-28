package com.bankpricechanges;

public class PriceFormatter
{
    public static String formatChange(PriceData data, BankPriceChangesConfig.DisplayMode mode)
    {
        String sign = data.getChange() >= 0 ? "+" : "";
        switch (mode)
        {
            case PERCENTAGE:
                return sign + String.format("%.1f%%", data.getChangePct());
            case GP_AMOUNT:
                return sign + formatGp(data.getChange());
            case BOTH:
                return sign + String.format("%.1f%%", data.getChangePct())
                    + " " + sign + formatGp(data.getChange());
            default:
                return "";
        }
    }

    public static String formatGp(int amount)
    {
        int abs = Math.abs(amount);
        if (abs >= 1_000_000)
        {
            return String.format("%.1fm", amount / 1_000_000.0);
        }
        if (abs >= 1_000)
        {
            return String.format("%.0fk", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }
}
