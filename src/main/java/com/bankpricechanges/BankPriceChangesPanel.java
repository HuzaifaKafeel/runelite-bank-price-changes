package com.bankpricechanges;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BankPriceChangesPanel extends PluginPanel
{
    private final BankPriceChangesPlugin plugin;
    private final BankPriceChangesConfig config;

    private boolean showGainers = true;
    private boolean showByPercent = true;
    private int displayCount;

    private List<PanelItemEntry> allEntries = new ArrayList<>();
    private JPanel itemListPanel;
    private JLabel updatedLabel;

    @Inject
    BankPriceChangesPanel(BankPriceChangesPlugin plugin, BankPriceChangesConfig config)
    {
        super(false);
        this.plugin = plugin;
        this.config = config;
        this.displayCount = config.panelItemCount().count;
        buildUi();
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("Bank Price Changes");
        title.setForeground(Color.WHITE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setBorder(new EmptyBorder(8, 0, 8, 0));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        JPanel gainerPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        gainerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        JButton gainersBtn = new JButton("Gainers");
        JButton losersBtn = new JButton("Losers");
        gainersBtn.setFocusPainted(false);
        losersBtn.setFocusPainted(false);
        gainersBtn.addActionListener(e -> { showGainers = true; rebuild(); });
        losersBtn.addActionListener(e -> { showGainers = false; rebuild(); });
        gainerPanel.add(gainersBtn);
        gainerPanel.add(losersBtn);

        JPanel modePanel = new JPanel(new GridLayout(1, 2, 4, 0));
        modePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        JButton pctBtn = new JButton("%");
        JButton gpBtn = new JButton("GP");
        pctBtn.setFocusPainted(false);
        gpBtn.setFocusPainted(false);
        pctBtn.addActionListener(e -> { showByPercent = true; rebuild(); });
        gpBtn.addActionListener(e -> { showByPercent = false; rebuild(); });
        modePanel.add(pctBtn);
        modePanel.add(gpBtn);

        JPanel countPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        countPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        JButton fiveBtn = new JButton("5");
        JButton tenBtn = new JButton("10");
        fiveBtn.setFocusPainted(false);
        tenBtn.setFocusPainted(false);
        fiveBtn.addActionListener(e -> { displayCount = 5; rebuild(); });
        tenBtn.addActionListener(e -> { displayCount = 10; rebuild(); });
        countPanel.add(fiveBtn);
        countPanel.add(tenBtn);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controls.setBorder(new EmptyBorder(0, 4, 4, 4));
        controls.add(gainerPanel);
        controls.add(Box.createVerticalStrut(4));
        controls.add(modePanel);
        controls.add(Box.createVerticalStrut(4));
        controls.add(countPanel);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.add(title, BorderLayout.NORTH);
        top.add(controls, BorderLayout.CENTER);

        itemListPanel = new JPanel();
        itemListPanel.setLayout(new BoxLayout(itemListPanel, BoxLayout.Y_AXIS));
        itemListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(itemListPanel);
        scroll.setBorder(null);
        scroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        updatedLabel = new JLabel("Updated: --:--:--");
        updatedLabel.setForeground(Color.GRAY);
        updatedLabel.setFont(updatedLabel.getFont().deriveFont(10f));

        JButton refreshBtn = new JButton("Refresh Now");
        refreshBtn.setFocusPainted(false);
        refreshBtn.addActionListener(e -> plugin.refreshNow());

        JPanel footer = new JPanel(new BorderLayout(4, 0));
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(new EmptyBorder(4, 4, 4, 4));
        footer.add(updatedLabel, BorderLayout.CENTER);
        footer.add(refreshBtn, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    public void updateData(List<PanelItemEntry> entries)
    {
        allEntries = entries;
        updatedLabel.setText("Updated: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        rebuild();
    }

    private void rebuild()
    {
        Comparator<PanelItemEntry> comparator = showByPercent
            ? Comparator.comparingDouble(e -> Math.abs(e.priceData.getChangePct()))
            : Comparator.comparingInt(e -> Math.abs(e.priceData.getChange()));
        comparator = comparator.reversed();

        itemListPanel.removeAll();

        allEntries.stream()
            .filter(e -> showGainers ? e.priceData.getChange() >= 0 : e.priceData.getChange() < 0)
            .sorted(comparator)
            .limit(displayCount)
            .forEach(e -> itemListPanel.add(buildRow(e)));

        itemListPanel.revalidate();
        itemListPanel.repaint();
    }

    private JPanel buildRow(PanelItemEntry entry)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(4, 6, 4, 6));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JLabel nameLabel = new JLabel(entry.itemName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(11f));

        boolean isGain = entry.priceData.getChange() >= 0;
        String sign = isGain ? "+" : "";
        String value = showByPercent
            ? sign + String.format("%.1f%%", entry.priceData.getChangePct())
            : sign + PriceFormatter.formatGp(entry.priceData.getChange());

        JLabel valueLabel = new JLabel(value);
        valueLabel.setForeground(isGain ? new Color(0, 200, 0) : Color.RED);
        valueLabel.setFont(valueLabel.getFont().deriveFont(11f));

        row.add(nameLabel, BorderLayout.CENTER);
        row.add(valueLabel, BorderLayout.EAST);

        return row;
    }

    public static class PanelItemEntry
    {
        final int itemId;
        final String itemName;
        final PriceData priceData;

        public PanelItemEntry(int itemId, String itemName, PriceData priceData)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.priceData = priceData;
        }
    }
}
