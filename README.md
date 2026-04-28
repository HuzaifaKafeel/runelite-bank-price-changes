# Bank Price Changes

A RuneLite plugin that overlays price change data directly on items in your bank,
and shows a side panel of your top gainers and losers.

Answers the question: "What happened to my bank value? Why is it up/down?!"

## Features

### Bank Overlay
Each bank item shows its price change in colour-coded text (green = up, red = down).
Choose between GP or % display, and toggle the overlay on or off from the RuneLite config panel.

### Side Panel
A panel showing your top **Gainers** or **Losers** over any time window from 5 minutes to 1 year.

Each item card shows:
- **Now** — the current GE price
- **Then** — the price at the start of the selected time window
- **Change** — how much the price moved (GP or %)

Configurable options:
- **Display** — sort and display by GP change or % change
- **Items** — show 5 or 10 items
- **Price** — sell offer (low) or buy offer (high)
- **Timestep** — 5 min, 1 hour, 6 hours, 24 hours, 1 week, 1 month, 1 year
- **Thresholds** — hide items below a minimum GP or % change
- **Placeholders** — include or exclude bank placeholder items

Hit **Refresh** in the panel footer to fetch the latest prices immediately.
