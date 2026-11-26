package dev.skytrade;

import dev.skytrade.bazaar.BazaarAnalyzer;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.execution.*;
import dev.skytrade.execution.sandbox.EnvironmentProbe;
import dev.skytrade.execution.sandbox.MockMarketAdapter;
import dev.skytrade.execution.sandbox.SandboxGuard;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.portfolio.PortfolioService;
import dev.skytrade.risk.CircuitBreaker;
import dev.skytrade.risk.SizingContext;
import dev.skytrade.simulation.MarketSimulator;
import dev.skytrade.simulation.SimOrder;
import dev.skytrade.util.TimeSource;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionTest {
    @Test
    void transitionTableRejectsIllegalMoves() {
        assertTrue(TradeState.IDLE.canTransitionTo(TradeState.ANALYZING));
        assertFalse(TradeState.IDLE.canTransitionTo(TradeState.SELL_ORDER_ACTIVE));
        assertFalse(TradeState.SALE_COMPLETED.canTransitionTo(TradeState.ERROR));
        assertFalse(TradeState.PARTIALLY_FILLED.canTransitionTo(TradeState.CANCELLED), "partial fills must be sold, not dropped");
        TradeStateMachine fsm = new TradeStateMachine(new TradeStore.InMemory(), () -> 0);
        Trade t = new Trade();
        t.id = "t";
        assertThrows(TradeStateMachine.IllegalTransition.class, () -> fsm.transition(t, TradeState.SALE_COMPLETED, ""));
        for (TradeState s : TradeState.values()) assertNotNull(s.allowed(), "every state has a transition row");
    }

    private record Harness(TimeSource.Manual clock, MarketSimulator sim, TradeStore store, PortfolioService portfolio, TradeEngine engine) {}

    private static Harness harness(TradeStore store, List<SimOrder> persisted, SkyTradeConfig cfg) {
        TimeSource.Manual clock = new TimeSource.Manual(1_000_000);
        MarketSimulator sim = new MarketSimulator(clock, 42, 0, o -> {
            persisted.removeIf(x -> x.id.equals(o.id));
            persisted.add(o);
        });
        sim.restore(new ArrayList<>(persisted));
        PortfolioService pf = new PortfolioService(cfg.sandbox.startingCapital);
        TradeEngine engine = new TradeEngine(() -> cfg, new MockMarketAdapter(sim), store, pf, new CircuitBreaker(), clock);
        return new Harness(clock, sim, store, pf, engine);
    }

    @Test
    void fullBazaarLifecycleWithRestartRecovery() {
        SkyTradeConfig cfg = new SkyTradeConfig();
        cfg.sandbox.repriceAfterMinutes = 1e9; // keep prices fixed for a deterministic test
        TradeStore store = new TradeStore.InMemory();
        List<SimOrder> persisted = new ArrayList<>();
        Harness h = harness(store, persisted, cfg);
        long t0 = h.clock().now();
        BazaarSnapshot s0 = TestData.snapshot(t0, TestData.product("ITEM", 100, 110, 700_000));
        h.sim().onBazaar(s0);
        BazaarOpportunity o = new BazaarAnalyzer(cfg).analyzeProduct(s0.get("ITEM"), List.of(), ItemCatalog.EMPTY, SizingContext.of(1e8), 0);
        Trade t = h.engine().open(o, 500, "TEST", true);
        h.engine().commit(t);
        assertEquals(TradeState.ORDER_PENDING, t.state);
        assertEquals(1e8 - 500 * o.entryPrice(), h.portfolio().cash(), 1e-6, "escrow booked");
        h.engine().tick(s0);
        assertEquals(TradeState.BUY_ORDER_ACTIVE, t.state);
        String firstOrder = t.buyOrderId;

        // Advance the market a few minutes: partial fills arrive.
        for (int i = 1; i <= 3; i++) {
            h.clock().advance(60_000);
            BazaarSnapshot s = TestData.snapshot(h.clock().now(), TestData.product("ITEM", 100, 110, 700_000));
            h.sim().onBazaar(s);
            h.engine().tick(s);
        }
        assertTrue(t.filledBuyQty > 0, "some fills after 3 minutes");

        // ---- simulate a game restart: new engine/portfolio, same persisted store and simulator orders ----
        Harness h2 = harness(store, persisted, cfg);
        h2.clock().set(h.clock().now());
        h2.engine().restore();
        Trade r = h2.engine().trades().iterator().next();
        assertEquals(TradeState.RECOVERY, r.state);
        double cashAfterRestart = h2.portfolio().cash();
        assertEquals(h.portfolio().cash(), cashAfterRestart, 1e-6, "ledger rebuilds identical cash");
        BazaarSnapshot s = TestData.snapshot(h2.clock().now(), TestData.product("ITEM", 100, 110, 700_000));
        h2.sim().onBazaar(s);
        h2.engine().tick(s);
        assertEquals(firstOrder, r.buyOrderId, "recovery reuses the existing order instead of placing a duplicate");
        assertTrue(r.state == TradeState.BUY_ORDER_ACTIVE || r.state == TradeState.PARTIALLY_FILLED || r.state == TradeState.BUY_COMPLETED);

        // Run until the trade completes.
        for (int i = 0; i < 2000 && r.state != TradeState.SALE_COMPLETED; i++) {
            h2.clock().advance(60_000);
            s = TestData.snapshot(h2.clock().now(), TestData.product("ITEM", 100, 110, 700_000));
            h2.sim().onBazaar(s);
            h2.engine().tick(s);
        }
        assertEquals(TradeState.SALE_COMPLETED, r.state, "trade finished: " + r.note);
        assertEquals(r.filledBuyQty, r.filledSellQty);
        double expected = r.filledSellQty * r.sellPrice * (1 - cfg.fees.bazaarTax) - r.costBasis;
        assertEquals(expected, r.realizedProfit(), 1e-3);
        assertEquals(1e8 + r.realizedProfit(), h2.portfolio().cash(), 1e-3, "all escrow released and profit realized exactly once");
        long ledgerKeys = store.loadLedger().stream().map(e -> e.tradeId() + "#" + e.seq()).distinct().count();
        assertEquals(store.loadLedger().size(), ledgerKeys, "no duplicate ledger entries");
    }

    @Test
    void buyTimeoutRefundsEscrow() {
        SkyTradeConfig cfg = new SkyTradeConfig();
        cfg.sandbox.cancelAfterHours = 1;
        Harness h = harness(new TradeStore.InMemory(), new ArrayList<>(), cfg);
        // No volume at all: the order can never fill.
        var dead = new dev.skytrade.market.BazaarProduct("DEAD", TestData.product("DEAD", 100, 110, 0).bids(),
                TestData.product("DEAD", 100, 110, 0).asks(), new dev.skytrade.market.QuickStatus(100, 1, 0, 1, 110, 1, 0, 1));
        BazaarSnapshot s = TestData.snapshot(h.clock().now(), dead);
        h.sim().onBazaar(s);
        var o = new BazaarAnalyzer(cfg).analyzeProduct(TestData.product("DEAD", 100, 110, 7000), List.of(), ItemCatalog.EMPTY, SizingContext.of(1e8), 0);
        Trade t = h.engine().open(o, 10, "TEST", true);
        h.engine().commit(t);
        h.engine().tick(s);
        assertEquals(TradeState.BUY_ORDER_ACTIVE, t.state);
        h.clock().advance(2 * 3_600_000L);
        h.engine().tick(TestData.snapshot(h.clock().now(), dead));
        assertEquals(TradeState.CANCELLED, t.state);
        assertEquals(1e8, h.portfolio().cash(), 1e-6);
    }

    @Test
    void simulatorPlacementIsIdempotent() {
        MarketSimulator sim = new MarketSimulator(() -> 0, 1, 0, null);
        sim.onBazaar(TestData.snapshot(1, TestData.product("X", 1, 2, 100)));
        SimOrder a = sim.place("client-1", SimOrder.Type.BAZAAR_BUY, "X", 10, 1.1, 0, 0);
        SimOrder b = sim.place("client-1", SimOrder.Type.BAZAAR_BUY, "X", 10, 1.1, 0, 0);
        assertSame(a, b);
        assertEquals(1, sim.openOrders().size());
    }

    private static EnvironmentProbe probe(EnvironmentProbe.Location l, String addr, String brand) {
        return new EnvironmentProbe() {
            public Location location() { return l; }
            public String serverAddress() { return addr; }
            public String serverBrand() { return brand; }
        };
    }

    @Test
    void sandboxGuardFailsClosed() {
        List<String> allow = List.of("test.local", "mc.hypixel.net");
        assertFalse(SandboxGuard.verify(probe(EnvironmentProbe.Location.MULTIPLAYER, "mc.hypixel.net", null), allow).allowed(), "Hypixel always denied, even allowlisted");
        assertFalse(SandboxGuard.verify(probe(EnvironmentProbe.Location.MULTIPLAYER, "play.example.com", null), allow).allowed());
        assertFalse(SandboxGuard.verify(probe(EnvironmentProbe.Location.MULTIPLAYER, "test.local", "Hypixel BungeeCord"), allow).allowed());
        assertFalse(SandboxGuard.verify(probe(EnvironmentProbe.Location.UNKNOWN, null, null), allow).allowed());
        assertFalse(SandboxGuard.verify(new EnvironmentProbe() {
            public Location location() { throw new IllegalStateException(); }
            public String serverAddress() { return null; }
            public String serverBrand() { return null; }
        }, allow).allowed());
        assertTrue(SandboxGuard.verify(probe(EnvironmentProbe.Location.MULTIPLAYER, "TEST.local:25565", "Paper"), allow).allowed());
        assertTrue(SandboxGuard.verify(probe(EnvironmentProbe.Location.SINGLEPLAYER, null, "vanilla"), allow).allowed());
        assertTrue(SandboxGuard.verify(probe(EnvironmentProbe.Location.MAIN_MENU, null, null), allow).allowed());
    }

    @Test
    void engineRefusesNonSimulatedAdapter() {
        TradingAdapter real = new TradingAdapter() {
            public OrderReport placeBuyOrder(String c, String i, long q, double p) { return null; }
            public OrderReport placeSellOffer(String c, String i, long q, double p) { return null; }
            public OrderReport status(String o) { return null; }
            public OrderReport cancel(String o) { return null; }
            public OrderReport buyAuction(String c, String u, double p) { return null; }
            public OrderReport createAuction(String c, String i, double p, double d, double s) { return null; }
            public boolean simulated() { return false; }
        };
        SkyTradeConfig cfg = new SkyTradeConfig();
        assertThrows(IllegalStateException.class, () -> new TradeEngine(() -> cfg, real, new TradeStore.InMemory(),
                new PortfolioService(1), new CircuitBreaker(), () -> 0));
    }
}
