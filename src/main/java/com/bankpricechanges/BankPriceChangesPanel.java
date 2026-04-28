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
import java.util.*;
import java.util.List;

public class BankPriceChangesPanel extends PluginPanel
{
    private static final Color COLOR_GAIN  = new Color(0, 200, 0);
    private static final Color COLOR_LOSS  = Color.RED;
    private static final Color COLOR_LABEL = new Color(150, 150, 150);
    private static final String CONFIG_GROUP = "bankpricechanges";

    private static final Map<BankPriceChangesConfig.TimePeriod, String> PERIOD_LABELS;
    static {
        PERIOD_LABELS = new LinkedHashMap<>();
        PERIOD_LABELS.put(BankPriceChangesConfig.TimePeriod.FIVE_MIN,          "5 min");
        PERIOD_LABELS.put(BankPriceChangesConfig.TimePeriod.ONE_HOUR,          "1 hour");
        PERIOD_LABELS.put(BankPriceChangesConfig.TimePeriod.SIX_HOURS,        "6 hours");
        PERIOD_LABELS.put(BankPriceChangesConfig.TimePeriod.TWENTY_FOUR_HOURS,"24 hours");
        PERIOD_LABELS.put(BankPriceChangesConfig.TimePeriod.ONE_WEEK,         "1 week");
        PERIOD_LABELS.put(BankPriceChangesConfig.TimePeriod.ONE_MONTH,        "1 month");
        PERIOD_LABELS.put(BankPriceChangesConfig.TimePeriod.ONE_YEAR,         "1 year");
    }

    private static final String[] SORT_OPTIONS       = {"GP change", "% change"};
    private static final String[] PRICE_MODE_OPTIONS = {"Sell offer (low)", "Buy offer (high)"};

    private final BankPriceChangesPlugin plugin;
    private final BankPriceChangesConfig config;
    private final ItemManager itemManager;
    private final ConfigManager configManager;

    private boolean showGainers   = true;
    private boolean showByPercent = false;
    private int displayCount;

    private List<PanelItemEntry> allEntries = new ArrayList<>();
    private JPanel itemListPanel;
    private JLabel updatedLabel;

    private JLabel gainersTab;
    private JLabel losersTab;
    private JComboBox<String>                                sortDropdown;
    private JComboBox<BankPriceChangesConfig.PanelItemCount> countDropdown;
    private JComboBox<String>                                priceModeDropdown;
    private JComboBox<BankPriceChangesConfig.TimePeriod>     timePeriodDropdown;
    private JButton placeholderBtn;
    private JTextField minPctField;
    private JTextField minGpField;

    private boolean syncingFromConfig = false;

    @Inject
    BankPriceChangesPanel(BankPriceChangesPlugin plugin, BankPriceChangesConfig config,
                          ItemManager itemManager, ConfigManager configManager)
    {
        super(false);
        this.plugin        = plugin;
        this.config        = config;
        this.itemManager   = itemManager;
        this.configManager = configManager;
        this.displayCount  = config.panelItemCount().count;
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
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));

        // ── Tab strip ─────────────────────────────────────────
        gainersTab = new JLabel("GAINERS", SwingConstants.CENTER);
        losersTab  = new JLabel("LOSERS",  SwingConstants.CENTER);
        gainersTab.setOpaque(true);
        losersTab.setOpaque(true);
        gainersTab.setFont(gainersTab.getFont().deriveFont(Font.BOLD, 14f));
        losersTab.setFont(losersTab.getFont().deriveFont(Font.BOLD, 14f));
        gainersTab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        losersTab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        gainersTab.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            { showGainers = true; updateTabAppearance(); rebuild(); }
        });
        losersTab.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            { showGainers = false; updateTabAppearance(); rebuild(); }
        });

        JPanel tabStrip = new JPanel(new GridLayout(1, 2));
        tabStrip.setBackground(ColorScheme.DARK_GRAY_COLOR);
        tabStrip.add(gainersTab);
        tabStrip.add(losersTab);

        // ── Dropdowns ─────────────────────────────────────────
        sortDropdown = makeDropdown(SORT_OPTIONS);
        sortDropdown.addActionListener(e ->
        {
            if (syncingFromConfig) return;
            showByPercent = sortDropdown.getSelectedIndex() == 1;
            configManager.setConfiguration(CONFIG_GROUP, "showByPercent", showByPercent);
            rebuild();
        });

        countDropdown = makeDropdown(BankPriceChangesConfig.PanelItemCount.values());
        countDropdown.addActionListener(e ->
        {
            if (syncingFromConfig) return;
            BankPriceChangesConfig.PanelItemCount sel =
                (BankPriceChangesConfig.PanelItemCount) countDropdown.getSelectedItem();
            if (sel == null) return;
            displayCount = sel.count;
            configManager.setConfiguration(CONFIG_GROUP, "panelItemCount", sel);
            rebuild();
        });

        priceModeDropdown = makeDropdown(PRICE_MODE_OPTIONS);
        priceModeDropdown.setToolTipText(
            "<html><b>Sell offer (low):</b> the lowest active sell price — what you receive when selling instantly<br>" +
            "<b>Buy offer (high):</b> the highest active buy price — what you pay when buying instantly</html>"
        );
        priceModeDropdown.addActionListener(e ->
        {
            if (syncingFromConfig) return;
            BankPriceChangesConfig.PriceMode mode = priceModeDropdown.getSelectedIndex() == 0
                ? BankPriceChangesConfig.PriceMode.LOW
                : BankPriceChangesConfig.PriceMode.HIGH;
            configManager.setConfiguration(CONFIG_GROUP, "priceMode", mode);
            rebuild();
        });

        timePeriodDropdown = makeDropdown(BankPriceChangesConfig.TimePeriod.values());
        timePeriodDropdown.setRenderer(new DefaultListCellRenderer()
        {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focus)
            {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof BankPriceChangesConfig.TimePeriod)
                    setText(PERIOD_LABELS.getOrDefault(value, value.toString()));
                setBackground(selected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
                setForeground(Color.WHITE);
                setBorder(new EmptyBorder(2, 6, 2, 6));
                return this;
            }
        });
        timePeriodDropdown.addActionListener(e ->
        {
            if (syncingFromConfig) return;
            BankPriceChangesConfig.TimePeriod sel =
                (BankPriceChangesConfig.TimePeriod) timePeriodDropdown.getSelectedItem();
            if (sel != null) setTimePeriod(sel);
        });

        // ── Controls: GridBagLayout so labels auto-size and dropdowns fill ──
        JPanel dropdownPanel = new JPanel(new GridBagLayout());
        dropdownPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        dropdownPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx  = 0;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(2, 0, 2, 8);

        GridBagConstraints dc = new GridBagConstraints();
        dc.gridx    = 1;
        dc.weightx  = 1.0;
        dc.fill     = GridBagConstraints.HORIZONTAL;
        dc.insets   = new Insets(2, 0, 2, 0);

        lc.gridy = 0; dc.gridy = 0;
        dropdownPanel.add(makeSmallLabel("Display:"),  lc);
        dropdownPanel.add(sortDropdown,                dc);

        lc.gridy = 1; dc.gridy = 1;
        dropdownPanel.add(makeSmallLabel("Items:"),    lc);
        dropdownPanel.add(countDropdown,               dc);

        lc.gridy = 2; dc.gridy = 2;
        dropdownPanel.add(makeSmallLabel("Price:"),    lc);
        dropdownPanel.add(priceModeDropdown,           dc);

        lc.gridy = 3; dc.gridy = 3;
        dropdownPanel.add(makeSmallLabel("Timestep:"), lc);
        dropdownPanel.add(timePeriodDropdown,          dc);

        // ── Threshold inputs ───────────────────────────────────
        minPctField = makeThresholdField(4);
        minGpField  = makeThresholdField(5);

        minPctField.addFocusListener(new FocusAdapter()
        { @Override public void focusLost(FocusEvent e) { commitMinPct(); } });
        minPctField.addActionListener(e -> commitMinPct());

        minGpField.addFocusListener(new FocusAdapter()
        { @Override public void focusLost(FocusEvent e) { commitMinGp(); } });
        minGpField.addActionListener(e -> commitMinGp());

        JPanel thresholdRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        thresholdRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        thresholdRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        thresholdRow.add(makeSmallLabel("Min %:"));
        thresholdRow.add(minPctField);
        thresholdRow.add(makeSmallLabel("Min GP:"));
        thresholdRow.add(minGpField);

        // ── Placeholder toggle on its own row ──────────────────
        placeholderBtn = makeControlButton("Placeholders ✓");
        placeholderBtn.setToolTipText("Include bank placeholder items in the overlay and panel");
        placeholderBtn.addActionListener(e ->
        {
            if (!syncingFromConfig)
                configManager.setConfiguration(CONFIG_GROUP, "includePlaceholders",
                    !config.includePlaceholders());
        });

        JPanel phRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        phRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        phRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        phRow.add(placeholderBtn);

        // ── Controls container ─────────────────────────────────
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controls.setBorder(new EmptyBorder(6, 6, 6, 6));
        controls.add(dropdownPanel);
        controls.add(Box.createVerticalStrut(6));
        controls.add(thresholdRow);
        controls.add(Box.createVerticalStrut(2));
        controls.add(phRow);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.add(title,    BorderLayout.NORTH);
        top.add(tabStrip, BorderLayout.CENTER);
        top.add(controls, BorderLayout.SOUTH);

        // ── Item list ──────────────────────────────────────────
        itemListPanel = new JPanel();
        itemListPanel.setLayout(new BoxLayout(itemListPanel, BoxLayout.Y_AXIS));
        itemListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemListPanel.setBorder(new EmptyBorder(4, 4, 4, 4));

        // Wrapper keeps items packed to the top inside the scroll pane
        JPanel listWrapper = new JPanel(new BorderLayout());
        listWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listWrapper.add(itemListPanel, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(listWrapper);
        scroll.setBorder(null);
        scroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // ── Footer ─────────────────────────────────────────────
        updatedLabel = new JLabel("Updated: --:--");
        updatedLabel.setForeground(Color.GRAY);
        updatedLabel.setFont(updatedLabel.getFont().deriveFont(13f));

        JButton refreshBtn = makeControlButton("Refresh");
        refreshBtn.setToolTipText("Refresh Now");
        refreshBtn.addActionListener(e -> plugin.refreshNow());

        JPanel footer = new JPanel(new BorderLayout(4, 0));
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(new EmptyBorder(4, 6, 4, 4));
        footer.add(updatedLabel, BorderLayout.CENTER);
        footer.add(refreshBtn,   BorderLayout.EAST);

        add(top,    BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        updateTabAppearance();
        updatePlaceholderButton();
        syncFromConfig();
    }

    // ── Helpers ───────────────────────────────────────────────

    private <T> JComboBox<T> makeDropdown(T[] items)
    {
        JComboBox<T> combo = new JComboBox<>(items);
        combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        combo.setForeground(Color.WHITE);
        combo.setFont(combo.getFont().deriveFont(13f));
        combo.setFocusable(false);
        combo.setRenderer(new DefaultListCellRenderer()
        {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focus)
            {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setBackground(selected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
                setForeground(Color.WHITE);
                setBorder(new EmptyBorder(2, 6, 2, 6));
                return this;
            }
        });
        return combo;
    }

    private JButton makeControlButton(String text)
    {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setFont(btn.getFont().deriveFont(13f));
        btn.setMargin(new Insets(2, 5, 2, 5));
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
        field.setFont(field.getFont().deriveFont(13f));
        field.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
        return field;
    }

    private JLabel makeSmallLabel(String text)
    {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.GRAY);
        lbl.setFont(lbl.getFont().deriveFont(13f));
        return lbl;
    }

    private void setTimePeriod(BankPriceChangesConfig.TimePeriod period)
    {
        configManager.setConfiguration(CONFIG_GROUP, "timePeriod", period);
    }

    private void commitMinPct()
    {
        if (syncingFromConfig) return;
        try
        {
            double val = Double.parseDouble(minPctField.getText().trim());
            configManager.setConfiguration(CONFIG_GROUP, "minThreshold", val);
        }
        catch (NumberFormatException ignored) {}
    }

    private void commitMinGp()
    {
        if (syncingFromConfig) return;
        try
        {
            int val = Integer.parseInt(minGpField.getText().trim());
            configManager.setConfiguration(CONFIG_GROUP, "minGpThreshold", val);
        }
        catch (NumberFormatException ignored) {}
    }

    // ── Config sync ───────────────────────────────────────────

    public void syncFromConfig()
    {
        syncingFromConfig = true;
        showByPercent = config.showByPercent();
        displayCount  = config.panelItemCount().count;

        sortDropdown.setSelectedIndex(showByPercent ? 1 : 0);
        countDropdown.setSelectedItem(config.panelItemCount());
        priceModeDropdown.setSelectedIndex(
            config.priceMode() == BankPriceChangesConfig.PriceMode.LOW ? 0 : 1);
        timePeriodDropdown.setSelectedItem(config.timePeriod());
        updatePlaceholderButton();

        minPctField.setText(String.valueOf(config.minThreshold()));
        minGpField.setText(String.valueOf(config.minGpThreshold()));
        syncingFromConfig = false;
        if (!allEntries.isEmpty()) rebuild();
    }

    // ── Appearance updaters ───────────────────────────────────

    private void updateTabAppearance()
    {
        if (showGainers)
        {
            gainersTab.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            gainersTab.setForeground(COLOR_GAIN);
            gainersTab.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 2, 0, COLOR_GAIN), new EmptyBorder(6, 0, 4, 0)));
            losersTab.setBackground(ColorScheme.DARK_GRAY_COLOR);
            losersTab.setForeground(Color.GRAY);
            losersTab.setBorder(new EmptyBorder(6, 0, 6, 0));
        }
        else
        {
            losersTab.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            losersTab.setForeground(COLOR_LOSS);
            losersTab.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 2, 0, COLOR_LOSS), new EmptyBorder(6, 0, 4, 0)));
            gainersTab.setBackground(ColorScheme.DARK_GRAY_COLOR);
            gainersTab.setForeground(Color.GRAY);
            gainersTab.setBorder(new EmptyBorder(6, 0, 6, 0));
        }
    }

    private void updatePlaceholderButton()
    {
        boolean on = config.includePlaceholders();
        placeholderBtn.setForeground(on ? COLOR_GAIN : Color.GRAY);
        placeholderBtn.setText(on ? "Placeholders ✓" : "Placeholders ✗");
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
        BankPriceChangesConfig.PriceMode mode = config.priceMode();

        Comparator<PanelItemEntry> comparator = showByPercent
            ? Comparator.comparingDouble(e -> Math.abs(e.priceData.getChangePct(mode)))
            : Comparator.comparingInt(e -> Math.abs(e.priceData.getChange(mode)));
        comparator = comparator.reversed();

        itemListPanel.removeAll();

        allEntries.stream()
            .filter(e ->
            {
                // Exclude items with no historical data for this period
                int prev = mode == BankPriceChangesConfig.PriceMode.HIGH
                    ? e.priceData.getPreviousHigh() : e.priceData.getPreviousLow();
                return prev != 0;
            })
            .filter(e -> showGainers
                ? e.priceData.getChange(mode) > 0
                : e.priceData.getChange(mode) < 0)
            .filter(e -> Math.abs(e.priceData.getChangePct(mode)) >= config.minThreshold())
            .filter(e -> Math.abs(e.priceData.getChange(mode))    >= config.minGpThreshold())
            .sorted(comparator)
            .limit(displayCount)
            .forEach(e ->
            {
                itemListPanel.add(buildCard(e, mode));
                itemListPanel.add(Box.createVerticalStrut(4));
            });

        itemListPanel.revalidate();
        itemListPanel.repaint();
    }

    private JPanel buildCard(PanelItemEntry entry, BankPriceChangesConfig.PriceMode mode)
    {
        int curPrice  = mode == BankPriceChangesConfig.PriceMode.HIGH
            ? entry.priceData.getCurrentHigh()  : entry.priceData.getCurrentLow();
        int prevPrice = mode == BankPriceChangesConfig.PriceMode.HIGH
            ? entry.priceData.getPreviousHigh() : entry.priceData.getPreviousLow();

        int change    = entry.priceData.getChange(mode);
        double pct    = entry.priceData.getChangePct(mode);

        boolean isGain    = change >= 0;
        Color accentColor = isGain ? COLOR_GAIN : COLOR_LOSS;
        String sign       = isGain ? "+" : "";

        // ── Card container ─────────────────────────────────────
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accentColor, 1),
            new EmptyBorder(5, 7, 5, 7)
        ));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ── Row 1: icon + item name ────────────────────────────
        JLabel spriteLabel = new JLabel();
        spriteLabel.setPreferredSize(new Dimension(36, 32));
        try
        {
            BufferedImage img = itemManager.getImage(entry.itemId, 1, false);
            if (img != null) spriteLabel.setIcon(new ImageIcon(img));
        }
        catch (Exception ignored) {}

        JLabel nameLabel = new JLabel(entry.itemName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));

        JPanel nameRow = new JPanel(new BorderLayout(6, 0));
        nameRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        nameRow.add(spriteLabel, BorderLayout.WEST);
        nameRow.add(nameLabel,   BorderLayout.CENTER);

        // ── Row 2: Now | Then | Change ────────────────────────
        JPanel priceRow = new JPanel(new GridLayout(1, 3, 2, 0));
        priceRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        priceRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        priceRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        priceRow.setBorder(new EmptyBorder(4, 0, 0, 0));

        String chgText = showByPercent
            ? sign + String.format("%.1f%%", pct)
            : sign + PriceFormatter.formatGp(change);

        priceRow.add(makePriceCell("Now",    PriceFormatter.formatGp(curPrice),  Color.WHITE));
        priceRow.add(makePriceCell("Then",   PriceFormatter.formatGp(prevPrice), Color.WHITE));
        priceRow.add(makePriceCell("Change", chgText,                            accentColor));

        card.add(nameRow);
        card.add(priceRow);

        // Hover highlight
        Color normalBg = ColorScheme.DARKER_GRAY_COLOR;
        Color hoverBg  = ColorScheme.DARK_GRAY_HOVER_COLOR;
        MouseAdapter hover = new MouseAdapter()
        {
            @Override public void mouseEntered(MouseEvent e)
            { setCardBackground(card, nameRow, priceRow, hoverBg); }
            @Override public void mouseExited(MouseEvent e)
            { setCardBackground(card, nameRow, priceRow, normalBg); }
        };
        card.addMouseListener(hover);
        nameRow.addMouseListener(hover);
        priceRow.addMouseListener(hover);

        return card;
    }

    private JPanel makePriceCell(String header, String value, Color valueColor)
    {
        JLabel headerLabel = new JLabel(header);
        headerLabel.setForeground(COLOR_LABEL);
        headerLabel.setFont(headerLabel.getFont().deriveFont(12f));

        JLabel valueLabel = new JLabel(value);
        valueLabel.setForeground(valueColor);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 13f));

        JPanel cell = new JPanel();
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        cell.add(headerLabel);
        cell.add(valueLabel);
        return cell;
    }

    private void setCardBackground(JPanel card, JPanel nameRow, JPanel priceRow, Color bg)
    {
        card.setBackground(bg);
        nameRow.setBackground(bg);
        priceRow.setBackground(bg);
        for (Component c : priceRow.getComponents())
        {
            if (c instanceof JPanel) ((JPanel) c).setBackground(bg);
        }
    }

    public static class PanelItemEntry
    {
        final int itemId;
        final String itemName;
        final PriceData priceData;

        public PanelItemEntry(int itemId, String itemName, PriceData priceData)
        {
            this.itemId    = itemId;
            this.itemName  = itemName;
            this.priceData = priceData;
        }
    }
}
