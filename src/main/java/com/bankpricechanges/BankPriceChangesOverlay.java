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

        Color color = data.getChange() >= 0 ? Color.GREEN : Color.RED;
        String sign = data.getChange() >= 0 ? "+" : "";

        Rectangle bounds = widgetItem.getCanvasBounds();
        int x = bounds.x + 1;
        int y = bounds.y + bounds.height - 1;

        graphics.setFont(FontManager.getRunescapeSmallFont());

        if (config.displayMode() == BankPriceChangesConfig.DisplayMode.BOTH)
        {
            String gpText  = sign + PriceFormatter.formatGp(data.getChange());
            String pctText = sign + String.format("%.1f%%", data.getChangePct());
            int lineHeight = graphics.getFontMetrics().getHeight();
            drawText(graphics, gpText, color, x, y);
            drawText(graphics, pctText, color, x, y - lineHeight);
        }
        else
        {
            drawText(graphics, formatChange(data, config.displayMode()), color, x, y);
        }
    }

    private void drawText(Graphics2D graphics, String text, Color color, int x, int y)
    {
        graphics.setColor(Color.BLACK);
        graphics.drawString(text, x + 1, y + 1);
        graphics.setColor(color);
        graphics.drawString(text, x, y);
    }

    private String formatChange(PriceData data, BankPriceChangesConfig.DisplayMode mode)
    {
        return PriceFormatter.formatChange(data, mode);
    }

    private String formatGp(int amount)
    {
        return PriceFormatter.formatGp(amount);
    }
}
