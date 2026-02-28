package com.bankpricechanges;

import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

public class BankPriceChangesOverlay extends WidgetItemOverlay
{
    private final BankPriceChangesPlugin plugin;
    private final BankPriceChangesConfig config;
    private final ItemManager itemManager;

    @Inject
    BankPriceChangesOverlay(BankPriceChangesPlugin plugin, BankPriceChangesConfig config, ItemManager itemManager)
    {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        showOnBank();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        PriceData data = plugin.getPriceChange(itemManager.canonicalize(itemId));
        if (data == null)
        {
            return;
        }

        if (Math.abs(data.getChangePct()) < config.minThreshold())
        {
            return;
        }

        String text = formatChange(data, config.displayMode());
        Color color = data.getChange() >= 0 ? Color.GREEN : Color.RED;

        Rectangle bounds = widgetItem.getCanvasBounds();
        int x = bounds.x + 1;
        int y = bounds.y + bounds.height - 1;

        graphics.setFont(FontManager.getRunescapeSmallFont());

        // Black shadow for readability
        graphics.setColor(Color.BLACK);
        graphics.drawString(text, x + 1, y + 1);

        // Colored text on top
        graphics.setColor(color);
        graphics.drawString(text, x, y);
    }

    private String formatChange(PriceData data, BankPriceChangesConfig.DisplayMode mode)
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

    private String formatGp(int amount)
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
