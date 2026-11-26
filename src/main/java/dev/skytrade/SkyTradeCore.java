package dev.skytrade;

import dev.skytrade.api.ApiStatus;
import dev.skytrade.api.HypixelApiClient;
import dev.skytrade.auction.AuctionAnalyzer;
import dev.skytrade.auction.AuctionFees;
import dev.skytrade.auction.AuctionIndex;
import dev.skytrade.auction.AuctionOpportunity;
import dev.skytrade.auction.ItemDecoder;
import dev.skytrade.bazaar.BazaarAnalyzer;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.ConfigManager;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.config.StrategyPreset;
import dev.skytrade.crafting.CraftFlipAnalyzer;
import dev.skytrade.crafting.CraftOpportunity;
import dev.skytrade.crafting.RecipeRepository;
import dev.skytrade.database.CsvExporter;
import dev.skytrade.database.Database;
import dev.skytrade.database.Repository;
import dev.skytrade.execution.Trade;
import dev.skytrade.execution.TradeEngine;
import dev.skytrade.execution.sandbox.AutoTrader;
import dev.skytrade.execution.sandbox.EnvironmentProbe;
import dev.skytrade.execution.sandbox.MockMarketAdapter;
import dev.skytrade.execution.sandbox.SandboxGuard;
import dev.skytrade.market.*;
import dev.skytrade.notifications.Notification;
import dev.skytrade.notifications.NotificationService;
import dev.skytrade.portfolio.PortfolioService;
import dev.skytrade.portfolio.PortfolioView;
import dev.skytrade.risk.RiskDecision;
import dev.skytrade.risk.RiskManager;
import dev.skytrade.simulation.Backtester;
import dev.skytrade.simulation.MarketSimulator;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.util.*;
import dev.skytrade.valuation.FairValueEstimator;
import dev.skytrade.valuation.SaleRecord;
import dev.skytrade.valuation.SalesHistory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Wires every subsystem together. Contains no Minecraft classes so it can run headless (tests).
 *
 * <p>Threads: API/compute work runs on {@link TaskManager}; every trade mutation happens on one
 * dedicated trader thread; SQLite runs on its own thread; the render thread only reads published,
 * immutable views ({@link #results()}, {@link PortfolioService#view()}).</p>
 */
public final class SkyTradeCore implements AutoCloseable {
    public record Results(List<BazaarOpportunity> bazaar, List<AuctionOpportunity> auctions, List<CraftOpportunity> crafts,
                          long bazaarComputedAt, long auctionComputedAt, long craftComputedAt) {
        static final Results EMPTY = new Results(List.of(), List.of(), List.of(), 0, 0, 0);

        public Opportunity best() {
            Opportunity best = null;
            for (List<? extends Opportunity> l : List.of(bazaar, auctions, crafts)) {
                for (Opportunity o : l) {
                    if (o.quantity() <= 0 || o.netProfit() <= 0) continue;
                    if (best == null || o.adjustedProfitPerHour() > best.adjustedProfitPerHour()) best = o;
                }
            }
            return best;
        }
    }

    private final Path dir;
    private final TimeSource time = TimeSource.SYSTEM;
    private final ConfigManager configManager;
    private final TaskManager tasks = new TaskManager();
    private final ExecutorService traderThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SkyTrade-Trader");
        t.setDaemon(true);
        return t;
    });
    private final Database database;
    private final Repository repo;
    private final ApiStatus apiStatus = new ApiStatus();
    private final HypixelApiClient api;
    private final MarketDataService market;
    private final RecipeRepository recipes = new RecipeRepository();
    private final NotificationService notifications;
    private final PortfolioService portfolio;
    private final RiskManager risk = new RiskManager();
    private final MarketSimulator simulator;
    private final TradeEngine engine;
    private final AutoTrader autoTrader;
    private final EnvironmentProbe probe;
    private final Deque<BazaarSnapshot> delayed = new ArrayDeque<>();
    private final List<ScheduledFuture<?>> schedules = new ArrayList<>();

    private volatile Results results = Results.EMPTY;
    private volatile AuctionIndex auctionIndex = AuctionIndex.EMPTY;
    private volatile List<Backtester.Result> backtest = List.of();
    private volatile String backtestStatus = "";
    private volatile boolean backtestCancel;
    private volatile long lastPortfolioSnapshot;
    private volatile boolean started;

    public SkyTradeCore(Path dir, EnvironmentProbe probe) {
        this.dir = dir;
        this.probe = probe;
        this.configManager = new ConfigManager(dir);
        configManager.load();
        this.database = new Database(dir.resolve("skytrade.db"));
        this.repo = new Repository(database);
        this.api = new HypixelApiClient(() -> config().api, apiStatus, time, tasks.computeExecutor());
        this.market = new MarketDataService(api, this::config, time, new ItemDecoder(120_000), new SalesHistory(200, 20_000),
                new MarketHistory(288, config().database.historyIntervalSeconds * 1000L));
        this.notifications = new NotificationService(this::config, time);
        this.portfolio = new PortfolioService(config().sandbox.startingCapital);
        this.simulator = new MarketSimulator(time, config().sandbox.rngSeed, config().sandbox.failureRate, o -> repo.saveSimOrder(o));
        this.engine = new TradeEngine(this::config, new MockMarketAdapter(simulator), repo, portfolio, risk.breaker(), time);
        this.autoTrader = new AutoTrader(this::config, engine, portfolio, risk, probe);
        engine.setNotifier(m -> notifications.post(Notification.Level.INFO, "Sandbox", m));
        engine.setOnComplete(repo::recordStrategyResult);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (started) return;
        started = true;
        database.open();
        Log.setErrorSink(e -> repo.logError(e[0], e[1], e[2]));
        tasks.run("startup", this::startupLoad);
    }

    private void startupLoad() {
        long now = time.now();
        // Restore recent history and sales so analysis has context immediately.
        repo.loadHistory(now - 24 * 3_600_000L, now, (id, p) -> market.history().load(id, p));
        int sales = 0;
        for (SaleRecord s : repo.loadSales(now - 7 * 86_400_000L)) if (market.sales().add(s)) sales++;
        Log.info("startup.history_loaded", "products", market.history().productCount(), "sales", sales);
        onTrader(() -> {
            simulator.restore(repo.loadSimOrders());
            engine.restore();
            publishPortfolio();
        });
        wireListeners();
        scheduleAll();
        tasks.run("recipes", this::loadRecipes);
    }

    private void wireListeners() {
        market.onHistory(points -> repo.saveBazaarSnapshot(market.bazaar(), new ArrayList<>(points.entrySet())));
        market.onBazaar(snap -> {
            notifications.checkPriceAlerts(snap);
            onTrader(() -> {
                synchronized (delayed) {
                    delayed.addLast(snap);
                    while (delayed.size() > 30) delayed.removeFirst();
                }
                simulator.onBazaar(snap);
                engine.tick(snap);
                publishPortfolio();
            });
            tasks.run("analysis.bazaar", this::analyzeBazaar);
        });
        market.onAuctions(snap -> {
            repo.saveAuctionSnapshot(snap.fetchedAt(), snap.lastUpdated(), snap.auctions().size(), snap.complete());
            onTrader(() -> simulator.onAuctions(snap));
            tasks.run("analysis.auctions", () -> analyzeAuctions(snap));
        });
        market.onSales(repo::saveSales);
    }

    private void scheduleAll() {
        SkyTradeConfig.Api a = config().api;
        schedules.add(tasks.every("poll.items", 0, 60_000, () -> {
            if (time.now() - market.catalog().fetchedAt() > config().api.itemsRefreshMinutes * 60_000L) market.refreshItems();
        }));
        schedules.add(tasks.every("poll.bazaar", 1_000, a.bazaarPollSeconds * 1000L, market::pollBazaar));
        schedules.add(tasks.every("poll.ended", 5_000, a.endedPollSeconds * 1000L, market::pollEnded));
        schedules.add(tasks.every("poll.auctions", 15_000, a.auctionPollSeconds * 1000L, market::pollAuctions));
        schedules.add(tasks.every("trader.tick", 10_000, 10_000, () -> onTrader(() -> {
            simulator.tickAuctions();
            engine.tick(simulator.bazaar());
            publishPortfolio();
        })));
        schedules.add(tasks.every("trader.decide", 20_000, Math.max(10, config().sandbox.decisionIntervalSeconds) * 1000L, () -> onTrader(this::decide)));
        schedules.add(tasks.every("craft.analysis", 30_000, 120_000, this::analyzeCrafts));
        schedules.add(tasks.every("db.cleanup", 60_000, 6 * 3_600_000L, () -> repo.cleanup(config().database, time.now())));
        schedules.add(tasks.every("memory.prune", 300_000, 3_600_000L, () -> onTrader(() -> {
            engine.prune(3 * 86_400_000L);
            simulator.prune(3 * 86_400_000L);
        })));
    }

    private void onTrader(Runnable r) {
        if (tasks.isShutdown()) return;
        try {
            traderThread.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.error("trader.task_failed", t);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    @Override
    public void close() {
        for (ScheduledFuture<?> f : schedules) f.cancel(false);
        backtestCancel = true;
        tasks.close();
        traderThread.shutdown();
        try {
            traderThread.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        configManager.save();
        database.close();
    }

    // ------------------------------------------------------------------ analysis

    private void analyzeBazaar() {
        BazaarSnapshot snap = market.bazaar();
        if (snap == null || !config().strategy.bazaarEnabled) return;
        long now = time.now();
        List<BazaarOpportunity> opps = new BazaarAnalyzer(config()).analyze(snap, market.history(), market.catalog(),
                portfolio.sizingContext(config().risk.orderSlots - engine.usedSlots(), config().sandbox.reinvestProfits), now);
        Results r = results;
        results = new Results(List.copyOf(opps), r.auctions(), r.crafts(), now, r.auctionComputedAt(), r.craftComputedAt());
        notifications.checkOpportunities(opps);
    }

    private void analyzeAuctions(AuctionSnapshot snap) {
        if (!config().strategy.auctionEnabled) return;
        long t0 = time.now();
        AuctionIndex idx = AuctionIndex.build(snap, market.decoder());
        auctionIndex = idx;
        FairValueEstimator est = new FairValueEstimator(market.sales(), this::componentPrice);
        double maxCap = portfolio.view().totalValue() > 0 ? portfolio.view().totalValue() * config().risk.maxCapitalPerTradePct
                : config().sandbox.startingCapital * config().risk.maxCapitalPerTradePct;
        List<AuctionOpportunity> opps = new AuctionAnalyzer(config()).analyze(idx, est, maxCap, time.now());
        for (int i = 0; i < Math.min(20, opps.size()); i++) {
            AuctionOpportunity o = opps.get(i);
            repo.saveValuation(time.now(), o.uuid(), o.itemId(), o.listingPrice(), o.valuation());
        }
        Results r = results;
        results = new Results(r.bazaar(), List.copyOf(opps), r.crafts(), r.bazaarComputedAt(), time.now(), r.craftComputedAt());
        Log.info("analysis.auctions", "decoded", idx.decodedCount(), "failed", idx.failedCount(), "groups", idx.groups().size(),
                "opportunities", opps.size(), "ms", time.now() - t0);
        notifications.checkOpportunities(opps);
    }

    private void analyzeCrafts() {
        if (!config().strategy.craftEnabled || recipes.size() == 0) return;
        BazaarSnapshot bz = market.bazaar();
        if (bz == null) return;
        double maxCap = Math.max(1, portfolio.view().totalValue()) * config().risk.maxCapitalPerTradePct;
        List<CraftOpportunity> opps = new CraftFlipAnalyzer(config()).analyze(recipes, bz, auctionIndex, market.sales(), market.catalog(), maxCap, time.now());
        Results r = results;
        results = new Results(r.bazaar(), r.auctions(), List.copyOf(opps), r.bazaarComputedAt(), r.auctionComputedAt(), time.now());
    }

    /** Conservative price for upgrade components: bazaar instant-sell, else lowest clean BIN. */
    public double componentPrice(String id) {
        BazaarSnapshot bz = market.bazaar();
        BazaarProduct p = bz == null ? null : bz.get(id);
        if (p != null && p.bestBid() > 0) return p.bestBid();
        return auctionIndex.lowestCleanBin(id);
    }

    // ------------------------------------------------------------------ trading

    private void decide() {
        SkyTradeConfig cfg = config();
        long now = time.now();
        BazaarSnapshot view = delayedView(now, cfg.sandbox.informationDelaySeconds * 1000L);
        Results r = results;
        List<BazaarOpportunity> bz = r.bazaar();
        if (view != null && view != market.bazaar() && cfg.mode == SkyTradeConfig.TradingMode.SANDBOX_AUTO) {
            // Re-analyse on the delayed view so the trader acts on stale-but-realistic information.
            bz = new BazaarAnalyzer(cfg).analyze(view, market.history(), market.catalog(),
                    portfolio.sizingContext(cfg.risk.orderSlots - engine.usedSlots(), cfg.sandbox.reinvestProfits), view.lastUpdated());
        }
        long age = view == null ? Long.MAX_VALUE / 4 : view.ageMs(now);
        autoTrader.decide(bz, r.auctions(), r.crafts(), market.catalog(), age, now);
        publishPortfolio();
    }

    private BazaarSnapshot delayedView(long now, long delayMs) {
        synchronized (delayed) {
            BazaarSnapshot best = null;
            for (BazaarSnapshot s : delayed) if (s.lastUpdated() <= now - delayMs) best = s;
            return best != null ? best : delayed.peekFirst();
        }
    }

    private void publishPortfolio() {
        long now = time.now();
        BazaarSnapshot bz = simulator.bazaar() != null ? simulator.bazaar() : market.bazaar();
        double tax = config().fees.bazaarTax;
        PortfolioView v = portfolio.publish(engine.trades(), t -> {
            BazaarProduct p = bz == null ? null : bz.get(t.itemId);
            if (p != null && p.bestBid() > 0) return p.bestBid() * (1 - tax);
            if (t.sellPrice > 0 && "AUCTION".equals(t.venue)) return AuctionFees.netProceeds(t.sellPrice, config().fees) * 0.9;
            return null;
        }, market.catalog(), now);
        if (now - lastPortfolioSnapshot > 5 * 60_000L) {
            lastPortfolioSnapshot = now;
            repo.savePortfolioSnapshot(v);
        }
    }

    /** Opens a manual paper position (simulated). Returns a future with a user-facing result message. */
    public CompletableFuture<String> paperTrade(Opportunity o) {
        CompletableFuture<String> f = new CompletableFuture<>();
        onTrader(() -> {
            SkyTradeConfig cfg = config();
            if (engine.hasActive(o instanceof AuctionOpportunity a ? a.uuid() : o.itemId())) {
                f.complete("Already tracking a position in " + o.name());
                return;
            }
            var ctx = portfolio.sizingContext(cfg.risk.orderSlots - engine.usedSlots(), cfg.sandbox.reinvestProfits);
            RiskDecision d = risk.validate(o, ctx, cfg.risk, market.catalog(), market.bazaarAgeMs(), cfg.api.staleAfterSeconds * 1000L);
            if (o.quantity() <= 0 || o.capital() > portfolio.cash()) {
                f.complete("Not enough paper capital for " + o.name());
                return;
            }
            Trade t = engine.open(o, o.quantity(), o.kind().name(), true);
            engine.commit(t);
            publishPortfolio();
            f.complete("Paper position opened: " + o.quantity() + "x " + o.name()
                    + (d.approved() ? "" : " (warning: " + String.join(", ", d.reasons()) + ")"));
        });
        return f;
    }

    public void cancelTrade(String id) {
        onTrader(() -> {
            engine.cancel(id);
            publishPortfolio();
        });
    }

    public void pause() {
        risk.breaker().pause();
        onTrader(() -> {
            engine.pauseAll("paused by user");
            publishPortfolio();
        });
    }

    public void resume() {
        risk.breaker().resume();
        config().risk.emergencyStop = false;
        onTrader(() -> {
            engine.resumeAll();
            publishPortfolio();
        });
    }

    public void emergencyStop() {
        config().risk.emergencyStop = true;
        configManager.save();
        pause();
    }

    public CompletableFuture<Void> resetPaper() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        onTrader(() -> {
            repo.clear();
            simulator.clear();
            engine.clear();
            portfolio.reset(config().sandbox.startingCapital);
            publishPortfolio();
            f.complete(null);
        });
        return f;
    }

    public CompletableFuture<List<Path>> exportCsv() {
        return tasks.supply("export", () -> CsvExporter.export(database, dir.resolve("exports")));
    }

    public void runBacktest(int days) {
        backtestCancel = false;
        backtestStatus = "running...";
        tasks.run("backtest", () -> {
            long to = time.now();
            long from = to - days * 86_400_000L;
            List<Backtester.Result> res = Backtester.compare(config(), (a, b) -> {
                List<Map.Entry<String, PricePoint>> out = new ArrayList<>();
                repo.loadHistory(a, b, (id, p) -> out.add(Map.entry(id, p)));
                return out;
            }, market.catalog(), from, to, List.of(StrategyPreset.CONSERVATIVE, StrategyPreset.BALANCED, StrategyPreset.AGGRESSIVE),
                    () -> backtestCancel);
            backtest = List.copyOf(res);
            backtestStatus = backtestCancel ? "cancelled" : "finished " + Fmt.age(time.now(), time.now());
        });
    }

    public void cancelBacktest() {
        backtestCancel = true;
    }

    // ------------------------------------------------------------------ recipes

    private void loadRecipes() {
        Path zip = dir.resolve("neu-repo.zip");
        Path user = dir.resolve("recipes.json");
        SkyTradeConfig.Api a = config().api;
        try {
            boolean stale = !Files.exists(zip) || time.now() - Files.getLastModifiedTime(zip).toMillis() > 86_400_000L;
            if (a.downloadRecipes && stale && a.recipeRepoUrl.startsWith("https://")) {
                HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(a.recipeRepoUrl)).timeout(Duration.ofMinutes(3))
                        .header("User-Agent", "SkyTrade-Mod/1.0").GET().build();
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() == 200) {
                    Path tmp = dir.resolve("neu-repo.zip.part");
                    try (InputStream in = resp.body()) {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
                    Log.info("recipes.downloaded", "bytes", Files.size(zip));
                } else {
                    resp.body().close();
                    Log.warn("recipes.download_failed", "status", resp.statusCode());
                }
            }
        } catch (Exception e) {
            Log.warn("recipes.download_failed", "error", e.getMessage());
        }
        recipes.load(zip, user);
        Log.info("recipes.ready", "outputs", recipes.size());
    }

    // ------------------------------------------------------------------ accessors

    public SkyTradeConfig config() { return configManager.get(); }
    public ConfigManager configManager() { return configManager; }
    public MarketDataService market() { return market; }
    public Results results() { return results; }
    public AuctionIndex auctionIndex() { return auctionIndex; }
    public PortfolioView portfolio() { return portfolio.view(); }
    public PortfolioService portfolioService() { return portfolio; }
    public NotificationService notifications() { return notifications; }
    public RiskManager risk() { return risk; }
    public AutoTrader.Status traderStatus() { return autoTrader.status(); }
    public SandboxGuard.Verdict sandboxVerdict() { return SandboxGuard.verify(probe, config().sandbox.privateServerAllowlist); }
    public Repository repository() { return repo; }
    public RecipeRepository recipes() { return recipes; }
    public List<Backtester.Result> backtestResults() { return backtest; }
    public String backtestStatus() { return backtestStatus; }
    public TaskManager tasks() { return tasks; }
    public Path directory() { return dir; }
    public TimeSource time() { return time; }
    public boolean databaseAvailable() { return database.available(); }
}
