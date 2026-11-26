# SkyTrade

A client-side Fabric mod that runs a trading-analysis terminal for the Hypixel SkyBlock economy
inside Minecraft. It covers Bazaar flipping, Auction House flipping, craft and upgrade arbitrage,
paper trading, backtesting, and a fully simulated private sandbox for autonomous trading.

> **SkyTrade never trades for you on Hypixel.** It only reads the public Hypixel API.
> It never sends commands, clicks or packets to a server. Every order it "places" goes to a local
> simulated market. Automated sandbox trading fails closed and refuses to run while you are
> connected to Hypixel, even if Hypixel is on your allowlist.

## Versions

| Component | Version |
| --- | --- |
| Minecraft | 1.21.11 |
| Java | 21 |
| Fabric Loader | 0.20.1 (requires >= 0.17) |
| Fabric API | 0.141.6+1.21.11 |
| Yarn mappings | 1.21.11+build.6 |
| Fabric Loom / Gradle | 1.14.10 / 9.2.1 (wrapper) |
| SQLite JDBC (bundled) | 3.47.1.0 |

These versions were checked against `meta.fabricmc.net` and `maven.fabricmc.net`. Every Minecraft
API the mod uses was compiled against the decompiled 1.21.11 Yarn sources: the input records
(`Click`, `KeyInput`, `CharInput`), `KeyBinding.Category`, `HudElementRegistry` and the
`Matrix3x2fStack` GUI transforms. Newer 26.x releases need Java 25, so they are not targeted.

## Build and install

```bash
./gradlew build
```

Copy `build/libs/skytrade-1.0.0.jar` into your `.minecraft/mods` folder together with Fabric API.
The SQLite driver is bundled inside the jar, so there are no other runtime dependencies.

Data lives in `.minecraft/config/skytrade/`:
- `config.json`: settings
- `skytrade.db`: market history, sales, trades, ledger
- `exports/`: CSV exports
- `neu-repo.zip`: downloaded recipes
- `recipes.json`: your own recipes (optional)

## Using it

| Command | Action |
| --- | --- |
| `/skytrade` | Open the terminal (dashboard) |
| `/skytrade bazaar`, `ah`, `deals`, `portfolio`, `settings` | Open a specific page |
| `/skytrade status` | API health, data age, portfolio, sandbox verdict in chat |
| `/skytrade pause` / `resume` | Pause or resume sandbox trading (resume also clears emergency stop) |
| `/skytrade resetpaper confirm` | Reset the paper/sandbox portfolio |
| `/skytrade export` | Export analytics tables to CSV |
| `/skytrade help` | Lists the commands and the Hypixel navigation commands |

Keybinds (Controls → SkyTrade): **K** opens the terminal. *Toggle HUD* and *Pause sandbox* start
unbound.

Detail pages show the matching Hypixel command (`/bz <item>`, `/viewauction <uuid>`), with a button
that copies it to your clipboard so you can type it yourself.

## Pages

- **Dashboard:** portfolio value, available and invested capital, daily and weekly profit,
  estimated profit per hour, a performance chart, endpoint health with the age of each dataset,
  top opportunities and recent transactions.
- **Bazaar:** a sortable, searchable table (entry, exit, net margin, volume, hold time, investment,
  profit, profit per hour, risk, confidence, score). Click a row for price history, an order-book
  depth chart, the top 12 levels on each side, every metric, and the reasoning behind the numbers.
- **Auction House:** underpriced BIN listings with fair value, net profit, ROI, expected sale time,
  comparables used, confidence and upgrades. The detail page plots comparable sales and the
  cheapest identical listings, and explains the valuation step by step.
- **Craft Flips:** profitable crafts from verified recipes, plus recombobulation upgrade flips,
  with per-material cost and how each material is acquired.
- **Orders:** every simulated order (type, quantity, price, progress, remaining quantity, age,
  expected profit, state), with cancel, pause, resume and emergency stop.
- **Portfolio:** cash, escrow, inventory, realized and unrealized P&L, a capital-allocation bar,
  and per-position performance.
- **Analytics:** win rate, predicted vs. actual profit, average hold, capital turnover, portfolio
  and daily-profit charts, profit by strategy and by item, largest losses, and a backtester that
  compares the Conservative, Balanced and Aggressive presets.
- **Settings:** every trading, risk, fee, strategy, sandbox, API, notification, HUD, appearance and
  retention parameter, plus the four strategy presets.

The HUD (portfolio, daily profit, best opportunity, active orders, API status) can be dragged,
scaled and faded, each line can be turned off, and it can be disabled entirely
(*Settings → HUD → Edit layout*).

## How the analysis works

### Market data
- Polls `/v2/skyblock/bazaar`, `/v2/skyblock/auctions?page=N`, `/v2/skyblock/auctions_ended` and
  `/v2/resources/skyblock/items`. None of these need an API key. An optional key is sent only as a
  header and is never logged or exported.
- Every request goes through a token-bucket rate limiter (honours `429` and `Retry-After`) and
  per-endpoint exponential backoff with jitter. Requests have timeouts, and responses are checked
  for `success` and `lastUpdated`.
- A dataset whose `lastUpdated` has not changed is skipped, never reprocessed. Every dataset's real
  age is shown, and data older than the stale threshold pauses automated trading.
- **Consistent AH pagination.** A full scan can straddle the API's refresh, so the scanner takes the
  newest `lastUpdated` it sees and refetches only the pages still on the older snapshot. It never
  mixes two snapshots. Listings are deduplicated by UUID.
- Decoded NBT is cached in a bounded LRU keyed by auction UUID. Raw item bytes are never stored;
  only derived fingerprints and prices are persisted.

### Bazaar engine
It works from the full order book, not just `quick_status`. A flip enters one tick above the best
buy order and exits one tick below the best sell offer, after the bazaar tax. Fill time comes from
weekly moving volume on the opposite side, multiplied by a capture share that shrinks with the
number of competing orders near the top of the book. The engine also estimates:
- price impact, by walking the bids for an emergency exit
- 24h volatility from stored history
- manipulation signals: robust z-scores against the historical median, depth collapses, abnormal
  spreads, and tiny bait offers

Ranking uses **risk- and confidence-adjusted profit per hour per million coins invested**. Capital
is floored at 1M and very small profits are scaled down, so neither tiny positions nor raw
percentage margin can dominate.

### Order sizing
Quantity is the minimum of these limits, and the binding one is shown:
- free cash after the emergency reserve
- maximum capital per trade
- maximum exposure per item
- maximum exposure per correlated group
- expected fill rate within half the holding limit
- 10% of daily volume
- maximum exit price impact
- the bazaar's per-order unit limit

### Auction House engine
Each listing is decoded from Base64/GZIP NBT by a small pure-Java reader that never touches game
classes. The decoder extracts: ID, rarity, reforge, enchantments, ultimate enchant, recombobulator,
dungeon and master stars, gemstones, hot and fuming potato books, ability scrolls, Art of War,
attributes, and pet type, rarity, level, held item and candy.

Items get three fingerprints (strict, relaxed, base), and fair value is worked out in this order:
1. Use **exact comparables** if there are enough of them.
2. Otherwise use relaxed comparables. Each one is normalised to a "clean" price by removing its own
   upgrades at a **partial recovery rate** (55% by default), then the listing's upgrades are
   credited back at the same rate. Upgrades are never added at full cost.
3. Drop outliers beyond 3.5 robust deviations, then take a recency-weighted median.
4. Cap the result by the cheapest identical BIN on the market.

The engine also estimates time to sale. It rejects any listing without enough comparables, and
every valuation comes with a line-by-line explanation.

Only the cheapest listing in each identical group is evaluated, so a 40k-listing scan stays cheap.

### Upgrade valuation
Each upgrade kind has its own recovery rate. The configured base rate (55%) is multiplied by a weight
per kind and capped at 95%. Gemstones, scrolls, pet items and master stars recover the most;
potato books, reforge stones and gem-slot unlocks the least. Upgrades that are valued:
- **every enchantment**, priced from bazaar books. A missing level is derived from the highest
  priced lower level (2 books of level N make N+1), with a 20% haircut. Only books worth less than
  5k are ignored (`strategy.minEnchantValue`), so cheap items (around 1M) are valued correctly too.
- recombobulator, hot and fuming potato books, Art of War/Peace, master stars, ability scrolls,
  power scrolls
- gemstones, plus the coins and gems spent **unlocking gemstone slots** (from the items API)
- Jalapeño Book, Wood Singularity, Transmission Tuners, Mana Disintegrators, Farming for Dummies,
  Polarvoid, Etherwarp, Divan powder coating, Book of Stats, dyes, talisman enrichments, drill parts,
  pet held items, and the stones of valuable reforges

Exact comparables are also corrected for smaller upgrade differences the fingerprint does not
cover.

### Coflnet price data
Hypixel only exposes the last ~60 seconds of sales, so without outside data the mod would need hours
before it could value anything. The free Coflnet SkyBlock API fills the gap. Only item tags and pet
rarity are sent.

| Endpoint | Used for | Frequency |
| --- | --- | --- |
| `/api/prices/neu` | lowest BIN of every AH item, used to price upgrades not on the bazaar | 1 request every 10 min |
| `/api/item/price/{tag}/history/week` | hourly 7-day history: confirms flips, catches pumps | best candidates of each scan, cached 30 min |
| `/api/flip/bazaar/spread`, `/api/craft/profit`, `/api/kat/profit` | the **Deals** page | 3 requests every 5 min |

- **One shared budget** (`external.maxRequestsPerMinute`, default 20). Coflnet blocks IPs that go
  over its limit (HTTP 403 `"blocked"`). When that happens, every Coflnet request pauses for
  30 minutes and `/skytrade status` shows `BLOCKED`.
- **Valuation from day one (market fallback):** a listing without enough local sales is valued from
  identical listings by other sellers (at least 2, the cheapest one undercut). The result is capped at
  1.25× the 7-day median plus the item's upgrades, and at most the 7-day high. It needs at least 10
  sales in 7 days. Confidence is capped at 60%, and discounts above 50% are still rejected. These
  flips show `mkt` in the Comps column.
- **Deals page** (`/skytrade deals`): Coflnet's lists are only leads, and every deal is re-checked
  against the mod's own live data:
  - **Bazaar spread:** flips Coflnet flags as manipulated are dropped. Entry and exit are
    recomputed from the live order book after tax.
  - **Crafts:** bazaar ingredients are re-priced at the live instant-buy price. The sale uses the
    lower of Coflnet's price and its median, after AH fees.
  - **Kat:** the pet's auction must still be active at that price in the mod's own AH scan.
    Materials are re-priced from the bazaar.
  - Deals that are no longer profitable disappear. *Type in chat* puts the command (`/bz`,
    `/viewrecipe`, `/viewauction`) into your chat box; you press Enter.

### Anti-manipulation
- **Wash trading:** sales in which the listing's own seller was the seller or the buyer are ignored.
  Repeated sales between the same two players count once, and each seller counts at most twice.
  Seller concentration lowers confidence.
- **7-day price history** (Coflnet SkyBlock API, only item tags and pet rarity are sent, at most 20
  requests per minute, cached for 30 minutes). Every AH candidate passes `MarketGuard`:
  - fewer than 10 sales in 7 days: rejected as illiquid
  - 24h average above 1.35× the 7-day median: pump, fair value is scaled back to the median
  - weekly trend below -15%: falling market, fair value is reduced
  - clean local value above 1.5× the 7-day high: rejected as manipulated comparables
  - discount above 50%: needs an exact match, confirmed history and a price not below half of the
    week's lows, otherwise it is rejected as too good to be true (bait or a different item)
- Without history a flip is shown as **(unconfirmed)**, gets lower confidence and no alert.
- All thresholds are in `config.json` under `external`.

### Manual assist
- Flip alerts in chat have a **[VIEW]** button. Clicking it only types `/viewauction …` into your chat
  box; you press Enter.
- Keybind *Type best AH flip into chat* (Controls → SkyTrade, unbound by default) does the same for
  the best confirmed flip.
- The mod itself never sends a command, click or packet. Buying, listing and pricing stay your own
  actions in the Hypixel menus.

> Hypixel only exposes the last ~60 seconds of completed sales, so SkyTrade collects sales while it
> runs and keeps them in SQLite. AH recommendations appear once enough comparable sales have been
> observed, usually after a few hours of play. Until then it rejects rather than guesses.

### Craft and upgrade flips
Recipes come only from verified sources: crafting-table recipes in the community NotEnoughUpdates
item repository (downloaded daily) and your own `recipes.json`. Items without a recipe are not
treated as craftable.

Costs are worked out recursively, taking the cheapest of buying (bazaar instant-buy or lowest clean
BIN) and crafting, with memoisation and cycle detection. Sale value is conservative: the bazaar
exit price after tax, or the median of clean AH sales capped by the lowest BIN and reduced by a
safety haircut. Recombobulation upgrade flips compare a clean copy plus a Recombobulator against
observed recombobulated sales.

`recipes.json` format:
```json
[{"output": "ENCHANTED_DIAMOND_BLOCK", "count": 1, "inputs": {"ENCHANTED_DIAMOND": 160}}]
```

## Trading modes

**Live Market Analysis** (default): data retrieval, opportunities, alerts, recommendations and
manual *paper trades*. Paper trades run in the local simulator, and nothing is automated.

**Private Sandbox Auto Trading**: autonomous selection, buy orders, partial fills, collection, sell
offers, repricing, cancellation, AH purchases and listings, profit reinvestment, capital
redistribution and automatic strategy selection. All of it runs against `MockMarketAdapter` and the
local `MarketSimulator`. `SandboxGuard` allows it only in:
- the main menu (offline simulation)
- singleplayer
- a server you allowlisted that is not Hypixel

Anything it cannot verify is denied. `TradeEngine` also refuses to start with any adapter that is
not simulated.

### State machine
Trades move through:

`IDLE → ANALYZING → VALIDATING → ORDER_PENDING → BUY_ORDER_ACTIVE → PARTIALLY_FILLED → BUY_COMPLETED → SELL_ORDER_ACTIVE → SALE_COMPLETED`

`CANCELLED`, `PAUSED`, `ERROR` and `RECOVERY` cover the exceptions. The complete transition table
is in `TradeState`, and anything not in it throws. Each state has its own timeout policy: validation
times out, buy orders are cancelled after a limit (partial fills are kept and sold), sells are
repriced and eventually exited at the bid, and errors move to recovery.

Persistence and recovery rules:
- Every transition is committed atomically with its ledger entries.
- Orders use deterministic client IDs (`trade:buy:attempt`), and the simulator is idempotent on
  them, so a retry or crash can never create a duplicate order.
- Fills come from cumulative reports, and ledger entries are unique on `(trade, seq)`, so nothing is
  counted twice.
- Cash is rebuilt from the ledger. After a restart every open trade enters `RECOVERY` and is
  reconciled against the persisted simulator orders.

### Market simulator
- Partial fills come from weekly flow.
- Competing orders reduce how much of the flow you capture.
- Orders behind the top of the book wait for the visible depth ahead of them.
- An order fills immediately when the market crosses it.
- Fill flow is noisy, and a configurable share of transactions fails.
- Contested AH snipes fail some of the time.
- Unsold listings expire and are relisted.
- Coins are held in escrow while a buy order is open.
- The trader can be given a configurable information delay.
- It is deterministic for a given seed.

### Backtesting
Replays stored history hour by hour. The analyzer only ever sees points timestamped at or before the
simulated clock, so there is no look-ahead. Each preset runs on an identical, independent
simulator, and the results show return, realized vs. predicted profit and max drawdown.

## Risk management
These limits are all configurable:
- capital per trade, exposure per item, correlated exposure, emergency reserve
- minimum net profit, ROI, liquidity and confidence
- maximum holding time, volatility and price impact
- maximum daily loss and simultaneous trades
- order slots, blacklist and whitelist

The circuit breaker halts automation on emergency stop, a manual pause, stale data, repeated errors
or the daily loss limit. When information is incomplete, the engine assumes the conservative case
(for example 10% volatility when history is short, or lower confidence for thin books).

## Architecture

```
dev.skytrade
├── api            HTTP client, rate limiter, backoff, status, tolerant JSON parsers
├── market         immutable snapshots, history, freshness, MarketDataService (central cache)
├── bazaar         order-book analytics, fees, BazaarAnalyzer
├── auction        NBT reader, decoder, fingerprints, index, fees, AuctionAnalyzer
├── valuation      upgrade valuation, sales history, FairValueEstimator
├── crafting       recipe repository, recursive cost calculator, CraftFlipAnalyzer
├── strategy       Opportunity contract, StrategySelector
├── risk           PositionSizer, RiskManager, CircuitBreaker, AnomalyDetector
├── portfolio      ledger-based PortfolioService, analytics
├── execution      TradeState, TradeStateMachine, TradeEngine, TradingAdapter, TradeStore
│   └── sandbox    MockMarketAdapter, SandboxGuard, AutoTrader, EnvironmentProbe
├── simulation     MarketSimulator, FillModel, Backtester
├── database       SQLite (single owner thread), Repository, CsvExporter
├── config         JSON config, presets
├── gui            terminal screen, pages, widgets (tables, charts), item icons
├── hud            overlay and layout editor
├── commands       /skytrade
├── notifications  alerts, delivered on the client thread
└── util           stats, formatting, logging, bounded caches, task manager
```

`SkyTradeCore` wires all of this together and contains no Minecraft classes, so it runs headless
(see `LiveApiTest`). Threads are kept separate:
- API and analysis work runs on a bounded pool.
- All trade mutations happen on one trader thread.
- SQLite runs on its own thread.
- The render thread only reads published immutable views.

## Database and retention

| Table | Contents |
| --- | --- |
| `market_snapshots` | metadata for each bazaar or AH snapshot |
| `bazaar_history` | product price and depth history |
| `auction_sales` | derived completed-sale records |
| `auction_valuations` | stored valuations |
| `trades`, `sim_orders`, `transactions` | simulated trading state and the ledger |
| `portfolio_snapshots` | portfolio value over time |
| `strategy_performance` | per-strategy results |
| `error_log` | warnings and errors |

Cleanup runs every 6 hours. Defaults: 14 days of market data, 365 days of transactions, 7 days of
errors. `/skytrade export` writes the analytics tables to CSV.

## Development

```bash
./gradlew test
```

```bash
./gradlew test -Dskytrade.live=true --tests dev.skytrade.LiveApiTest
```

```bash
./gradlew runClient -PopenOnStart=DASHBOARD
```

- `test` runs the unit tests. They need no network.
- The second command is the opt-in end-to-end run against the public API.
- `runClient` with `-PopenOnStart` opens the terminal over the title screen, a debugging aid.

The unit tests cover:
- NBT decoding of real items
- parsers with malformed input
- robust statistics, the rate limiter and backoff
- bazaar metrics, manipulation flags and sizing limits
- fair value (outliers, BIN cap, upgrade recovery, look-ahead protection)
- recipe parsing and recursive cost with cycles
- the transition table
- a full simulated trade lifecycle across a restart, with no duplicate orders or ledger entries and
  exact cash reconciliation
- buy timeouts and escrow refunds
- sandbox guard fail-closed cases

## Known limitations
- Fees use Hypixel's published defaults: 1.25% bazaar tax, tiered AH listing fees, 1% claim tax
  above 1M. Account upgrades such as Bazaar Flipper change these; adjust them in Settings.
- The fill model estimates flow from weekly moving volume. Real fills depend on other players and
  can differ, which is why predicted and actual results are tracked separately.
- Backtests use stored top-of-book history, not full order books, and model competition
  conservatively.
- Portfolio tracking is for the paper/sandbox portfolio. The mod does not read your real Hypixel
  orders or inventory.
