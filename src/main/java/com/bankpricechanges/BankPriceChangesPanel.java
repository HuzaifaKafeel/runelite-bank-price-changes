package com.bankpricechanges;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BankPriceChangesPanel extends PluginPanel
{
    private static final Color COLOR_GAIN = new Color(0, 200, 0);
    private static final Color COLOR_LOSS = Color.RED;
    private static final String CONFIG_GROUP = "bankpricechanges";

    private final BankPriceChangesPlugin plugin;
    private final BankPriceChangesConfig config;
    private final ItemManager itemManager;
    private final ConfigManager configManager;

    // Panel-local display state
    private boolean showGainers = true;
    private boolean showByPercent = true;
    private int displayCount;

    private List<PanelItemEntry> allEntries = new ArrayList<>();
    private JPanel itemListPanel;
    private JLabel updatedLabel;

    // UI refs for appearance sync
    private JLabel gainersTab;
    private JLabel losersTab;
    private JButton pctBtn;
    private JButton gpBtn;
    private JButton fiveBtn;
    private JButton tenBtn;
    private JButton fiveMinBtn;
    private JButton oneHourBtn;
    private JButton sixHourBtn;
    private JButton twentyFourHourBtn;
    private JButton placeholderBtn;
    private JTextField minPctField;
    private JTextField minGpField;

    // Guard against re-entrant config writes during syncFromConfig
    private boolean syncingFromConfig = false;

    @Inject
    BankPriceChangesPanel(BankPriceChangesPlugin plugin, BankPriceChangesConfig config,
                          ItemManager itemManager, ConfigManager configManager)
    {
        super(false);
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.configManager = configManager;
        this.displayCount = config.panelItemCount().count;
        buildUi();
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // ── Title ─────────────────────────────────────────────
        JLabel title = new JLabel("BANK PRICE CHANGES");
        title.setForeground(Color.WHITE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setBorder(new EmptyBorder(8, 0, 6, 0));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));

        // ── Tab strip (Gainers / Losers) ──────────────────────
        gainersTab = new JLabel("GAINERS", SwingConstants.CENTER);
        losersTab = new JLabel("LOSERS", SwingConstants.CENTER);
        gainersTab.setOpaque(true);
        losersTab.setOpaque(true);
        gainersTab.setFont(gainersTab.getFont().deriveFont(Font.BOLD, 12f));
        losersTab.setFont(losersTab.getFont().deriveFont(Font.BOLD, 12f));
        gainersTab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        losersTab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        gainersTab.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                showGainers = true;
                updateTabAppearance();
                rebuild();
            }
        });
        losersTab.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                showGainers = false;
                updateTabAppearance();
                rebuild();
            }
        });

        JPanel tabStrip = new JPanel(new GridLayout(1, 2));
        tabStrip.setBackground(ColorScheme.DARK_GRAY_COLOR);
        tabStrip.add(gainersTab);
        tabStrip.add(losersTab);

        // ── Row 1: Sort mode [%][GP] and count [5][10] ────────
        pctBtn = makeControlButton("%");
        gpBtn = makeControlButton("GP");
        fiveBtn = makeControlButton("5");
        tenBtn = makeControlButton("10");

        pctBtn.addActionListener(e -> { showByPercent = true; updateSortButtons(); rebuild(); });
        gpBtn.addActionListener(e -> { showByPercent = false; updateSortButtons(); rebuild(); });
        fiveBtn.addActionListener(e -> { displayCount = 5; updateCountButtons(); rebuild(); });
        tenBtn.addActionListener(e -> { displayCount = 10; updateCountButtons(); rebuild(); });

        JPanel sortRow = makeControlRow(new JComponent[]{pctBtn, gpBtn}, new JComponent[]{fiveBtn, tenBtn});

        // ── Row 2: Time period and placeholder toggle ──────────
        fiveMinBtn = makeControlButton("5m");
        oneHourBtn = makeControlButton("1h");
        sixHourBtn = makeControlButton("6h");
        twentyFourHourBtn = makeControlButton("24h");
        placeholderBtn = makeControlButton("PH \u2713");
        placeholderBtn.setToolTipText("Include bank placeholder items");

        fiveMinBtn.addActionListener(e ->
        {
            if (!syncingFromConfig)
            {
                configManager.setConfiguration(CONFIG_GROUP, "timePeriod",
                    BankPriceChangesConfig.TimePeriod.FIVE_MIN);
            }
        });
        oneHourBtn.addActionListener(e ->
        {
            if (!syncingFromConfig)
            {
                configManager.setConfiguration(CONFIG_GROUP, "timePeriod",
                    BankPriceChangesConfig.TimePeriod.ONE_HOUR);
            }
        });
        sixHourBtn.addActionListener(e ->
        {
            if (!syncingFromConfig)
            {
                configManager.setConfiguration(CONFIG_GROUP, "timePeriod",
                    BankPriceChangesConfig.TimePeriod.SIX_HOURS);
            }
        });
        twentyFourHourBtn.addActionListener(e ->
        {
            if (!syncingFromConfig)
            {
                configManager.setConfiguration(CONFIG_GROUP, "timePeriod",
                    BankPriceChangesConfig.TimePeriod.TWENTY_FOUR_HOURS);
            }
        });
        placeholderBtn.addActionListener(e ->
        {
            if (!syncingFromConfig)
            {
                configManager.setConfiguration(CONFIG_GROUP, "includePlaceholders",
                    !config.includePlaceholders());
            }
        });

        JPanel timePHRow = makeControlRow(
            new JComponent[]{fiveMinBtn, oneHourBtn, sixHourBtn, twentyFourHourBtn},
            new JComponent[]{placeholderBtn}
        );

        // ── Row 3: Min threshold inputs ────────────────────────
        minPctField = makeThresholdField(4);
        minGpField = makeThresholdField(6);

        minPctField.addFocusListener(new FocusAdapter()
        {
            @Override
            public void focusLost(FocusEvent e)
            {
                commitMinPct();
            }
        });
        minPctField.addActionListener(e -> commitMinPct());

        minGpField.addFocusListener(new FocusAdapter()
        {
            @Override
            public void focusLost(FocusEvent e)
            {
                commitMinGp();
            }
        });
        minGpField.addActionListener(e -> commitMinGp());

        JPanel thresholdRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        thresholdRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        thresholdRow.add(makeSmallLabel("Min %:"));
        thresholdRow.add(minPctField);
        thresholdRow.add(makeSmallLabel("Min GP:"));
        thresholdRow.add(minGpField);

        // ── Controls container ─────────────────────────────────
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controls.setBorder(new EmptyBorder(2, 4, 4, 4));
        controls.add(sortRow);
        controls.add(timePHRow);
        controls.add(thresholdRow);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.add(title, BorderLayout.NORTH);
        top.add(tabStrip, BorderLayout.CENTER);
        top.add(controls, BorderLayout.SOUTH);

        // ── Item list ──────────────────────────────────────────
        itemListPanel = new JPanel();
        itemListPanel.setLayout(new BoxLayout(itemListPanel, BoxLayout.Y_AXIS));
        itemListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(itemListPanel);
        scroll.setBorder(null);
        scroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // ── Footer ─────────────────────────────────────────────
        updatedLabel = new JLabel("Updated: --:--");
        updatedLabel.setForeground(Color.GRAY);
        updatedLabel.setFont(updatedLabel.getFont().deriveFont(11f));

        JButton refreshBtn = new JButton("\u21BB");
        refreshBtn.setFocusPainted(false);
        refreshBtn.setFont(refreshBtn.getFont().deriveFont(14f));
        refreshBtn.setToolTipText("Refresh Now");
        refreshBtn.addActionListener(e -> plugin.refreshNow());

        JPanel footer = new JPanel(new BorderLayout(4, 0));
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(new EmptyBorder(4, 6, 4, 4));
        footer.add(updatedLabel, BorderLayout.CENTER);
        footer.add(refreshBtn, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        updateTabAppearance();
        updateSortButtons();
        updateCountButtons();
        syncFromConfig();
    }

    // ── Helpers ───────────────────────────────────────────────

    private JPanel makeControlRow(JComponent[] leftBtns, JComponent[] rightBtns)
    {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.setBackground(ColorScheme.DARK_GRAY_COLOR);
        for (JComponent c : leftBtns)
        {
            left.add(c);
        }

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.setBackground(ColorScheme.DARK_GRAY_COLOR);
        for (JComponent c : rightBtns)
        {
            right.add(c);
        }

        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(new EmptyBorder(2, 0, 2, 0));
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private JButton makeControlButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setFont(btn.getFont().deriveFont(11f));
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        btn.setForeground(Color.GRAY);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        return btn;
    }

    private JTextField makeThresholdField(int cols)
    {
        JTextField field = new JTextField(cols);
        field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setFont(field.getFont().deriveFont(11f));
        field.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
        return field;
    }

    private JLabel makeSmallLabel(String text)
    {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.GRAY);
        lbl.setFont(lbl.getFont().deriveFont(11f));
        return lbl;
    }

    private void commitMinPct()
    {
        if (syncingFromConfig)
        {
            return;
        }
        try
        {
            double val = Double.parseDouble(minPctField.getText().trim());
            configManager.setConfiguration(CONFIG_GROUP, "minThreshold", val);
        }
        catch (NumberFormatException ignored)
        {
        }
    }

    private void commitMinGp()
    {
        if (syncingFromConfig)
        {
            return;
        }
        try
        {
            int val = Integer.parseInt(minGpField.getText().trim());
            configManager.setConfiguration(CONFIG_GROUP, "minGpThreshold", val);
        }
        catch (NumberFormatException ignored)
        {
        }
    }

    // ── Config sync ───────────────────────────────────────────

    /** Called from EDT whenever any bankpricechanges config key changes. */
    public void syncFromConfig()
    {
        syncingFromConfig = true;
        updateTimePeriodButtons();
        updatePlaceholderButton();
        minPctField.setText(String.valueOf(config.minThreshold()));
        minGpField.setText(String.valueOf(config.minGpThreshold()));
        syncingFromConfig = false;
        if (!allEntries.isEmpty())
        {
            rebuild();
        }
    }

    // ── Appearance updaters ───────────────────────────────────

    private void updateTabAppearance()
    {
        if (showGainers)
        {
            gainersTab.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            gainersTab.setForeground(COLOR_GAIN);
            gainersTab.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 2, 0, COLOR_GAIN),
                new EmptyBorder(6, 0, 4, 0)
            ));
            losersTab.setBackground(ColorScheme.DARK_GRAY_COLOR);
            losersTab.setForeground(Color.GRAY);
            losersTab.setBorder(new EmptyBorder(6, 0, 6, 0));
        }
        else
        {
            losersTab.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            losersTab.setForeground(COLOR_LOSS);
            losersTab.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 2, 0, COLOR_LOSS),
                new EmptyBorder(6, 0, 4, 0)
            ));
            gainersTab.setBackground(ColorScheme.DARK_GRAY_COLOR);
            gainersTab.setForeground(Color.GRAY);
            gainersTab.setBorder(new EmptyBorder(6, 0, 6, 0));
        }
    }

    private void updateSortButtons()
    {
        pctBtn.setForeground(showByPercent ? Color.WHITE : Color.GRAY);
        gpBtn.setForeground(showByPercent ? Color.GRAY : Color.WHITE);
    }

    private void updateCountButtons()
    {
        fiveBtn.setForeground(displayCount == 5 ? Color.WHITE : Color.GRAY);
        tenBtn.setForeground(displayCount == 10 ? Color.WHITE : Color.GRAY);
    }

    private void updateTimePeriodButtons()
    {
        BankPriceChangesConfig.TimePeriod current = config.timePeriod();
        fiveMinBtn.setForeground(current == BankPriceChangesConfig.TimePeriod.FIVE_MIN
            ? Color.WHITE : Color.GRAY);
        oneHourBtn.setForeground(current == BankPriceChangesConfig.TimePeriod.ONE_HOUR
            ? Color.WHITE : Color.GRAY);
        sixHourBtn.setForeground(current == BankPriceChangesConfig.TimePeriod.SIX_HOURS
            ? Color.WHITE : Color.GRAY);
        twentyFourHourBtn.setForeground(current == BankPriceChangesConfig.TimePeriod.TWENTY_FOUR_HOURS
            ? Color.WHITE : Color.GRAY);
    }

    private void updatePlaceholderButton()
    {
        boolean on = config.includePlaceholders();
        placeholderBtn.setForeground(on ? COLOR_GAIN : Color.GRAY);
        placeholderBtn.setText(on ? "PH \u2713" : "PH \u2717");
    }

    // ── Data ──────────────────────────────────────────────────

    public void updateData(List<PanelItemEntry> entries)
    {
        allEntries = entries;
        updatedLabel.setText("Updated: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
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
            .filter(e -> Math.abs(e.priceData.getChangePct()) >= config.minThreshold())
            .filter(e -> Math.abs(e.priceData.getChange()) >= config.minGpThreshold())
            .sorted(comparator)
            .limit(displayCount)
            .forEach(e -> itemListPanel.add(buildRow(e)));

        itemListPanel.revalidate();
        itemListPanel.repaint();
    }

    private JPanel buildRow(PanelItemEntry entry)
    {
        boolean isGain = entry.priceData.getChange() >= 0;
        Color accentColor = isGain ? COLOR_GAIN : COLOR_LOSS;

        JPanel row = new JPanel(new BorderLayout(0, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setPreferredSize(new Dimension(0, 36));

        // 3px colored accent stripe
        JPanel stripe = new JPanel();
        stripe.setBackground(accentColor);
        stripe.setPreferredSize(new Dimension(3, 36));
        stripe.setOpaque(true);

        // Item sprite
        JLabel spriteLabel = new JLabel();
        try
        {
            BufferedImage img = itemManager.getImage(entry.itemId, 1, false);
            if (img != null)
            {
                spriteLabel.setIcon(new ImageIcon(img));
            }
        }
        catch (Exception ignored)
        {
        }

        // WEST: stripe + struts + sprite
        JPanel west = new JPanel();
        west.setLayout(new BoxLayout(west, BoxLayout.X_AXIS));
        west.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        west.add(stripe);
        west.add(Box.createHorizontalStrut(4));
        west.add(spriteLabel);
        west.add(Box.createHorizontalStrut(6));

        // CENTER: item name
        JLabel nameLabel = new JLabel(entry.itemName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(12f));

        // EAST: change value
        String sign = isGain ? "+" : "";
        String value = showByPercent
            ? sign + String.format("%.1f%%", entry.priceData.getChangePct())
            : sign + PriceFormatter.formatGp(entry.priceData.getChange());

        JLabel valueLabel = new JLabel(value);
        valueLabel.setForeground(accentColor);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 12f));
        valueLabel.setBorder(new EmptyBorder(0, 0, 0, 6));

        row.add(west, BorderLayout.WEST);
        row.add(nameLabel, BorderLayout.CENTER);
        row.add(valueLabel, BorderLayout.EAST);

        // Hover highlight
        Color normalBg = ColorScheme.DARKER_GRAY_COLOR;
        Color hoverBg = ColorScheme.DARK_GRAY_HOVER_COLOR;
        row.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                row.setBackground(hoverBg);
                west.setBackground(hoverBg);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                row.setBackground(normalBg);
                west.setBackground(normalBg);
            }
        });

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
