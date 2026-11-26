package dev.skytrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.skytrade.auction.AuctionOpportunity;
import dev.skytrade.auction.Fingerprint;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.execution.sandbox.EnvironmentProbe;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.valuation.SaleRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Live forward test: runs the headless core against the real Hypixel API for a while and records every
 * opportunity it finds plus every sale Hypixel reports, so the predictions can be checked against what
 * really happened afterwards (benchmark/analyze.py). Nothing is traded; this only observes.
 *
 * <pre>./gradlew test -Dskytrade.forward=true -Dskytrade.forwardMinutes=60 --tests dev.skytrade.ForwardTest</pre>
 */
@EnabledIfSystemProperty(named = "skytrade.forward", matches = "true")
class ForwardTest {
    record AhRow(long ts, String uuid, String itemId, String strict, String relaxed, long price, double fair, double sale,
                 double net, double conf, String level, boolean confirmed, String seller, List<String> why) {}

    record BzRow(long ts, String itemId, double entry, double exit, long qty, double net, double holdHours, double risk,
                 double conf, List<String> flags) {}

    record BookRow(long ts, String itemId, double bid, double ask, double sellWeek, double buyWeek) {}

    @Test
    void record() throws Exception {
        long minutes = Long.getLong("skytrade.forwardMinutes", 60L);
        Path out = Path.of(System.getProperty("skytrade.forwardOut", "build/forward"));
        Files.createDirectories(out);
        Path dir = Files.createTempDirectory("skytrade-forward");
        EnvironmentProbe offline = new EnvironmentProbe() {
            public Location location() { return Location.MAIN_MENU; }
            public String serverAddress() { return null; }
            public String serverBrand() { return null; }
        };
        SkyTradeCore core = new SkyTradeCore(dir, offline);
        SkyTradeConfig cfg = core.config();
        cfg.api.downloadRecipes = false;
        cfg.mode = SkyTradeConfig.TradingMode.LIVE_ANALYSIS;
        // Share Coflnet's per-IP limit with the data collector that may run at the same time.
        cfg.external.maxRequestsPerMinute = Integer.getInteger("skytrade.forwardCoflnetRpm", 6);
        core.start();

        Map<String, AhRow> ah = new LinkedHashMap<>();
        List<BzRow> bz = new ArrayList<>();
        List<BookRow> books = new ArrayList<>();
        Set<String> watched = new HashSet<>();
        long end = System.currentTimeMillis() + minutes * 60_000L;
        long lastBz = 0, lastBook = 0, lastAh = 0;
        while (System.currentTimeMillis() < end) {
            long now = System.currentTimeMillis();
            var res = core.results();
            if (res.auctionComputedAt() > lastAh) {
                lastAh = res.auctionComputedAt();
                for (AuctionOpportunity o : res.auctions()) {
                    ah.putIfAbsent(o.uuid(), new AhRow(now, o.uuid(), o.itemId(), Fingerprint.strict(o.attrs()), Fingerprint.relaxed(o.attrs()),
                            o.listingPrice(), o.valuation().fairValue(), o.valuation().salePrice(), o.netProfit(), o.confidence(),
                            o.valuation().matchLevel(), o.confirmed(), o.seller(), o.explanation()));
                }
            }
            if (res.bazaarComputedAt() > lastBz && now - lastBz > 5 * 60_000L) {
                lastBz = now;
                for (BazaarOpportunity o : res.bazaar().subList(0, Math.min(40, res.bazaar().size()))) {
                    bz.add(new BzRow(now, o.itemId(), o.entryPrice(), o.exitPrice(), o.quantity(), o.netProfit(), o.holdHours(),
                            o.risk(), o.confidence(), o.flags()));
                    watched.add(o.itemId());
                }
            }
            var snap = core.market().bazaar();
            if (snap != null && snap.lastUpdated() > lastBook) {
                lastBook = snap.lastUpdated();
                for (String id : watched) {
                    BazaarProduct p = snap.get(id);
                    if (p != null) books.add(new BookRow(snap.lastUpdated(), id, p.bestBid(), p.bestAsk(),
                            p.quick().sellMovingWeek(), p.quick().buyMovingWeek()));
                }
            }
            Thread.sleep(5_000);
        }
        // Everything Hypixel reported as sold while we ran, with fingerprints (persisted by the core).
        Thread.sleep(3_000);
        List<SaleRecord> sales = core.repository().loadSales(0);
        Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
        try (Writer w = Files.newBufferedWriter(out.resolve("forward.json"))) {
            Map<String, Object> dump = new LinkedHashMap<>();
            dump.put("minutes", minutes);
            dump.put("ah", ah.values());
            dump.put("bazaar", bz);
            dump.put("books", books);
            dump.put("sales", sales);
            dump.put("coflnet", core.external().status());
            dump.put("deals", core.deals().deals());
            gson.toJson(dump, w);
        }
        System.out.println("=== forward: " + ah.size() + " AH opportunities, " + bz.size() + " bazaar rows, " + sales.size() + " sales, "
                + core.external().status());
        core.close();
    }
}
