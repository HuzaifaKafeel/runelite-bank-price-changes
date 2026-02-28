package com.bankpricechanges;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BankPriceChangesPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(BankPriceChangesPlugin.class);
        RuneLite.main(args);
    }
}
