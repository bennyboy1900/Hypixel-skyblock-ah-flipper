package dev.skytrade;

import dev.skytrade.auction.AuctionOpportunity;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.execution.sandbox.EnvironmentProbe;
import dev.skytrade.util.Fmt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end run of the headless core against the real public API. Disabled unless -Dskytrade.live=true. */
@EnabledIfSystemProperty(named = "skytrade.live", matches = "true")
class LiveApiTest {
    @Test
    void endToEnd() throws Exception {
        Path dir = Files.createTempDirectory("skytrade-live");
        EnvironmentProbe offline = new EnvironmentProbe() {
            public Location location() { return Location.MAIN_MENU; }
            public String serverAddress() { return null; }
            public String serverBrand() { return null; }
        };
        SkyTradeCore core = new SkyTradeCore(dir, offline);
        SkyTradeConfig cfg = core.config();
        cfg.api.downloadRecipes = false;
        cfg.mode = SkyTradeConfig.TradingMode.SANDBOX_AUTO;
        cfg.sandbox.autoTrade = true;
        cfg.sandbox.decisionIntervalSeconds = 10;
        cfg.sandbox.informationDelaySeconds = 0;
        core.start();
        assertTrue(core.databaseAvailable(), "SQLite opened");
        long deadline = System.currentTimeMillis() + 150_000;
        while (System.currentTimeMillis() < deadline && (core.results().bazaar().isEmpty() || core.market().auctions().auctions().isEmpty()))
            Thread.sleep(1000);
        Thread.sleep(40_000); // let ended-auction polling, analysis and a few trader decisions run
        var res = core.results();
        System.out.println("=== bazaar: " + core.market().bazaar().products().size() + " products, " + res.bazaar().size() + " opportunities, data age "
                + Fmt.duration(core.market().bazaarAgeMs()));
        for (BazaarOpportunity o : res.bazaar().subList(0, Math.min(12, res.bazaar().size()))) {
            System.out.printf("  %-28s entry %-8s exit %-8s qty %-6d net %-8s pph %-8s hold %-8s risk %s conf %s lim=%s%n", Fmt.prettyId(o.itemId()),
                    Fmt.coins(o.entryPrice()), Fmt.coins(o.exitPrice()), o.quantity(), Fmt.coins(o.netProfit()), Fmt.coins(o.profitPerHour()),
                    Fmt.duration(o.holdHours() * 3.6e6), Fmt.score(o.risk()), Fmt.score(o.confidence()), o.sizingConstraint());
        }
        var snap = core.market().auctions();
        System.out.println("=== auctions: " + snap.auctions().size() + " listings / " + snap.totalAuctions() + " total, pages " + snap.pages()
                + ", complete " + snap.complete() + ", decoded " + core.auctionIndex().decodedCount() + " failed " + core.auctionIndex().failedCount()
                + ", groups " + core.auctionIndex().groups().size());
        System.out.println("=== sales tracked: " + core.market().sales().totalSales() + ", AH opportunities " + res.auctions().size());
        for (AuctionOpportunity o : res.auctions().subList(0, Math.min(5, res.auctions().size())))
            System.out.println("  " + o.name() + " " + Fmt.coins(o.listingPrice()) + " -> " + Fmt.coins(o.valuation().salePrice()) + " net " + Fmt.coins(o.netProfit()));
        var pv = core.portfolio();
        System.out.println("=== sandbox: " + core.traderStatus().reason() + " | active trades " + pv.activeTrades().size() + " | cash " + Fmt.coins(pv.cash())
                + " escrow " + Fmt.coins(pv.escrow()) + " value " + Fmt.coins(pv.totalValue()));
        pv.activeTrades().forEach(t -> System.out.println("  " + t.name + " " + t.state + " " + t.targetQty + " @ " + t.buyPrice + " filled " + t.filledBuyQty));
        System.out.println("=== api: " + core.market().status().overall());
        assertFalse(res.bazaar().isEmpty());
        assertFalse(snap.auctions().isEmpty());
        assertEquals(cfg.sandbox.startingCapital, pv.totalValue(), cfg.sandbox.startingCapital * 0.05, "value conserved within 5% early on");
        var files = core.exportCsv().get();
        System.out.println("=== exported " + files.size() + " CSV files");
        core.close();
    }
}
