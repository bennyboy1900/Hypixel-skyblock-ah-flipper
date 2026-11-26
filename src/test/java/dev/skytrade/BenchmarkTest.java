package dev.skytrade;

import com.google.gson.*;
import dev.skytrade.api.ApiParsers;
import dev.skytrade.auction.AuctionFees;
import dev.skytrade.auction.Fingerprint;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.auction.ItemDecoder;
import dev.skytrade.bazaar.BazaarAnalyzer;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.bazaar.OrderBook;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.config.StrategyPreset;
import dev.skytrade.market.*;
import dev.skytrade.risk.SizingContext;
import dev.skytrade.simulation.Backtester;
import dev.skytrade.valuation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Historical benchmarks on real recorded market data (see benchmark/record_hypixel.py and collect.py).
 * Every benchmark walks forward in time: a decision at time t only ever sees data from before t.
 * Results are written as JSON to build/bench for benchmark/report.py.
 *
 * <pre>./gradlew test -Dskytrade.bench=true --tests dev.skytrade.BenchmarkTest</pre>
 */
@EnabledIfSystemProperty(named = "skytrade.bench", matches = "true")
class BenchmarkTest {
    private static final Path DATA = Path.of(System.getProperty("skytrade.benchData", "benchmark/data"));
    private static final Path OUT = Path.of("build/bench");
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    // ------------------------------------------------------------------ data loading

    private static BufferedReader gz(Path p) throws IOException {
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(p)), StandardCharsets.UTF_8));
    }

    /** Reads lines of a gzip file that may still be being written (the unfinished tail is ignored). */
    private static List<String> lines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = gz(p)) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (EOFException e) {
            // recording still running: the last line is incomplete
        }
        return out;
    }

    record Sale(String uuid, long ts, long price, boolean bin, String seller, String buyer, ItemAttributes attrs) {}

    static List<Sale> loadSales() throws IOException {
        Map<String, Sale> byId = new LinkedHashMap<>();
        Path p = DATA.resolve("hypixel_ended.jsonl.gz");
        List<String> all = lines(p);
        int lines = all.size();
        {
            for (String line : all) {
                JsonObject o;
                try {
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue; // a line cut off by an interrupted recording
                }
                for (JsonElement el : o.getAsJsonArray("auctions")) {
                    JsonObject a = el.getAsJsonObject();
                    String id = a.get("auction_id").getAsString();
                    if (byId.containsKey(id)) continue;
                    ItemAttributes at;
                    try {
                        at = ItemDecoder.decodeUncached(a.get("item_bytes").getAsString(), null);
                    } catch (Exception e) {
                        continue;
                    }
                    if (at == null || at.count() != 1) continue;
                    byId.put(id, new Sale(id, a.get("timestamp").getAsLong(), a.get("price").getAsLong(), a.get("bin").getAsBoolean(),
                            str(a, "seller"), str(a, "buyer"), at));
                }
            }
        }
        List<Sale> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingLong(Sale::ts));
        System.out.println("loaded " + out.size() + " sales from " + lines + " polls");
        return out;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static List<BazaarSnapshot> loadBazaar() throws IOException {
        List<BazaarSnapshot> out = new ArrayList<>();
        {
            for (String line : lines(DATA.resolve("hypixel_bazaar.jsonl.gz"))) {
                JsonObject o;
                try {
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                JsonObject products = new JsonObject();
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("products").entrySet()) {
                    JsonObject src = e.getValue().getAsJsonObject();
                    JsonObject p = new JsonObject();
                    p.addProperty("product_id", e.getKey());
                    p.add("quick_status", src.get("q"));
                    p.add("sell_summary", src.get("s"));
                    p.add("buy_summary", src.get("b"));
                    products.add(e.getKey(), p);
                }
                JsonObject root = new JsonObject();
                root.add("products", products);
                root.add("lastUpdated", o.get("lastUpdated"));
                try {
                    out.add(ApiParsers.bazaar(root, o.get("fetched").getAsLong()));
                } catch (Exception ignored) {
                }
            }
        }
        out.sort(Comparator.comparingLong(BazaarSnapshot::lastUpdated));
        System.out.println("loaded " + out.size() + " bazaar snapshots");
        return out;
    }

    static ItemCatalog catalog() throws Exception {
        Path cache = DATA.resolve("hypixel_items.json.gz");
        if (!Files.exists(cache)) {
            HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/resources/skyblock/items"))
                    .header("User-Agent", "SkyTrade-Benchmark/1.0").build(), HttpResponse.BodyHandlers.ofByteArray());
            try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(cache))) {
                o.write(resp.body());
            }
        }
        try (BufferedReader r = gz(cache)) {
            return ApiParsers.items(JsonParser.parseReader(r).getAsJsonObject(), 0);
        }
    }

    /** Component prices at time t: the latest bazaar snapshot not after t (instant-sell price). */
    static PriceSource pricesAt(List<BazaarSnapshot> books, long t, Map<String, Double> lowestBins) {
        BazaarSnapshot snap = null;
        for (BazaarSnapshot s : books) {
            if (s.lastUpdated() <= t) snap = s;
            else break;
        }
        BazaarSnapshot fs = snap != null ? snap : books.isEmpty() ? null : books.get(0);
        return id -> {
            BazaarProduct p = fs == null ? null : fs.get(id);
            if (p != null && p.bestBid() > 0) return p.bestBid();
            Double l = lowestBins.get(id);
            return l == null ? Double.NaN : l;
        };
    }

    static Map<String, Double> lowestBins() throws IOException {
        Path p = DATA.resolve("coflnet_lowest_bin.json.gz");
        if (!Files.exists(p)) return Map.of();
        try (BufferedReader r = gz(p)) {
            return dev.skytrade.external.ExternalPriceService.parseNeuPrices(JsonParser.parseReader(r).getAsJsonObject());
        }
    }

    static void write(String name, Object o) throws IOException {
        Files.createDirectories(OUT);
        try (Writer w = Files.newBufferedWriter(OUT.resolve(name + ".json"))) {
            GSON.toJson(o, w);
        }
    }

    // ------------------------------------------------------------------ AH: valuation accuracy + flip simulation

    record Prediction(String uuid, String item, long ts, long actual, double fair, double sale, double conf, String level,
                      boolean bin, int comps) {}

    record Variant(String name, double recovery, double minEnchant, boolean washFilter) {}

    @Test
    void auctionHouseWalkForward() throws Exception {
        List<Sale> sales = loadSales();
        List<BazaarSnapshot> books = loadBazaar();
        ItemCatalog cat = catalog();
        Map<String, Double> lbin = lowestBins();
        List<Variant> variants = List.of(
                new Variant("default (55% recovery, enchants >= 5k)", 0.55, 5_000, true),
                new Variant("old threshold (enchants >= 150k)", 0.55, 150_000, true),
                new Variant("upgrades ignored (0% recovery)", 0.0, 5_000, true),
                new Variant("full upgrade cost (95% recovery)", 0.95 / 1.6, 5_000, true),
                new Variant("no wash-trade filter", 0.55, 5_000, false));
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("sales", sales.size());
        results.put("bins", sales.stream().filter(Sale::bin).count());
        results.put("spanHours", sales.isEmpty() ? 0 : (sales.get(sales.size() - 1).ts() - sales.get(0).ts()) / 3.6e6);
        results.put("items", sales.stream().map(s -> s.attrs().skyblockId()).distinct().count());
        List<Object> perVariant = new ArrayList<>();
        // Price sources per 10-minute bucket, so lookups stay cheap.
        Map<Long, PriceSource> priceCache = new HashMap<>();
        for (Variant v : variants) {
            SalesHistory h = new SalesHistory(400, 50_000);
            List<Prediction> preds = new ArrayList<>();
            for (Sale s : sales) {
                long bucket = s.ts() / 600_000L;
                PriceSource prices = priceCache.computeIfAbsent(bucket, b -> pricesAt(books, s.ts(), lbin));
                FairValueEstimator est = new FairValueEstimator(h, prices, cat);
                var p = new FairValueEstimator.Params(5, v.recovery(), 0.01, 7 * 86_400_000L, 2 * 86_400_000L, v.minEnchant());
                Valuation val = est.estimate(s.attrs(), List.of(), p, s.ts() - 1, v.washFilter() ? s.seller() : null);
                if (val.valid()) {
                    preds.add(new Prediction(s.uuid(), s.attrs().skyblockId(), s.ts(), s.price(), val.fairValue(), val.salePrice(),
                            val.confidence(), val.matchLevel(), s.bin(), val.comparables()));
                }
                List<String> ups = new ArrayList<>();
                for (UpgradeComponent c : UpgradeValuer.components(s.attrs(), cat)) ups.add(c.encode());
                ups.add(SaleRecord.FULL_UPGRADES);
                h.add(new SaleRecord(s.uuid(), s.ts(), s.price(), s.bin(), s.attrs().skyblockId(), Fingerprint.base(s.attrs()),
                        Fingerprint.relaxed(s.attrs()), Fingerprint.strict(s.attrs()), ups,
                        v.washFilter() ? s.seller() : null, v.washFilter() ? s.buyer() : null));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("variant", v.name());
            r.put("predictions", preds);
            perVariant.add(r);
            System.out.println("variant " + v.name() + ": " + preds.size() + " valued");
        }
        results.put("variants", perVariant);
        results.put("flips", simulateAuctionFlips(sales, books, cat, lbin, new SkyTradeConfig().strategy.ahNoiseMargin));
        // Same simulation without the noise rule, to measure what it changes.
        results.put("flipsWithoutNoiseRule", simulateAuctionFlips(sales, books, cat, lbin, 0));
        write("ah_walkforward", results);
    }

    record FlipOutcome(String item, long ts, long buy, double predictedSale, double predictedNet, String level, double conf,
                       String outcome, double realizedNet, int laterSales, double hoursToSell) {}

    /**
     * Treats every real BIN sale as a listing the bot could have bought at that price. The bot buys when its
     * walk-forward valuation says the flip clears the configured minimum profit, ROI and confidence. The
     * outcome comes from the identical items that really sold afterwards:
     * <ul>
     *   <li>a later identical sale at or above our relist price: sold at our price,</li>
     *   <li>later identical sales only below it: we follow the market down to their median,</li>
     *   <li>no identical sale until the data ends: unsold (capital stuck, reported separately).</li>
     * </ul>
     */
    static List<FlipOutcome> simulateAuctionFlips(List<Sale> sales, List<BazaarSnapshot> books, ItemCatalog cat, Map<String, Double> lbin,
                                                  double noiseMargin) {
        SkyTradeConfig cfg = new SkyTradeConfig();
        SalesHistory h = new SalesHistory(400, 50_000);
        Map<String, List<Sale>> byStrict = new HashMap<>();
        for (Sale s : sales) if (s.bin()) byStrict.computeIfAbsent(Fingerprint.strict(s.attrs()), k -> new ArrayList<>()).add(s);
        List<FlipOutcome> out = new ArrayList<>();
        Map<Long, PriceSource> priceCache = new HashMap<>();
        for (Sale s : sales) {
            PriceSource prices = priceCache.computeIfAbsent(s.ts() / 600_000L, b -> pricesAt(books, s.ts(), lbin));
            if (s.bin()) {
                var p = new FairValueEstimator.Params((int) cfg.strategy.ahMinComparables, cfg.strategy.upgradeRecoveryRate,
                        cfg.strategy.ahUndercut, 7 * 86_400_000L, 2 * 86_400_000L, cfg.strategy.minEnchantValue);
                // Live, the bot sees the cheapest identical BIN listings and caps its resale price there. Identical
                // items that sold in the next 15 minutes were already listed at this moment, so they stand in
                // for those competing listings (this can only lower the bot's resale price).
                List<Long> competing = new ArrayList<>();
                for (Sale o : byStrict.getOrDefault(Fingerprint.strict(s.attrs()), List.of())) {
                    if (o.ts() > s.ts() && o.ts() <= s.ts() + 15 * 60_000L && !o.uuid().equals(s.uuid())) competing.add(o.price());
                }
                Valuation v = new FairValueEstimator(h, prices, cat).estimate(s.attrs(), competing, p, s.ts() - 1, s.seller());
                if (v.valid()) {
                    double sale = v.salePrice();
                    double fees = AuctionFees.listingFee(sale, cfg.fees) + AuctionFees.claimTax(sale, cfg.fees);
                    double net = sale - fees - s.price();
                    double roi = net / s.price();
                    if (net >= cfg.risk.minNetProfit && roi >= cfg.risk.minRoi && v.confidence() >= cfg.risk.minConfidence
                            && v.saleHours() <= cfg.strategy.ahMaxSaleHours
                            && dev.skytrade.auction.AuctionAnalyzer.clearsNoise(net, sale, v.volatility(), noiseMargin)) {
                        List<Sale> later = new ArrayList<>();
                        for (Sale o : byStrict.getOrDefault(Fingerprint.strict(s.attrs()), List.of())) {
                            if (o.ts() > s.ts() && !o.uuid().equals(s.uuid())) later.add(o);
                        }
                        String outcome;
                        double realized = Double.NaN, hours = Double.NaN;
                        Sale hit = null;
                        for (Sale o : later) if (o.price() >= sale) { hit = o; break; }
                        if (hit != null) {
                            outcome = "sold";
                            realized = net;
                            hours = (hit.ts() - s.ts()) / 3.6e6;
                        } else if (!later.isEmpty()) {
                            double[] lp = later.stream().mapToDouble(Sale::price).toArray();
                            double med = dev.skytrade.util.Stats.median(lp);
                            outcome = "sold_lower";
                            realized = AuctionFees.netProceeds(med, cfg.fees) - s.price();
                            hours = (later.get(later.size() / 2).ts() - s.ts()) / 3.6e6;
                        } else {
                            outcome = "unsold";
                        }
                        out.add(new FlipOutcome(s.attrs().skyblockId(), s.ts(), s.price(), sale, net, v.matchLevel(), v.confidence(),
                                outcome, realized, later.size(), hours));
                    }
                }
            }
            List<String> ups = new ArrayList<>();
            for (UpgradeComponent c : UpgradeValuer.components(s.attrs(), cat)) ups.add(c.encode());
            ups.add(SaleRecord.FULL_UPGRADES);
            h.add(new SaleRecord(s.uuid(), s.ts(), s.price(), s.bin(), s.attrs().skyblockId(), Fingerprint.base(s.attrs()),
                    Fingerprint.relaxed(s.attrs()), Fingerprint.strict(s.attrs()), ups, s.seller(), s.buyer()));
        }
        return out;
    }

    // ------------------------------------------------------------------ bazaar: replay on recorded order books

    record BzOutcome(String item, long ts, long qty, double entry, double exit, double predictedNet, double predictedHours,
                     double bought, double sold, double cost, double revenue, double liquidation, double realizedNet,
                     int repricesBuy, int repricesSell, double risk, double conf, List<String> flags) {}

    /**
     * Every 10 minutes, runs the real BazaarAnalyzer on the recorded book (with history from earlier snapshots
     * only) and follows its top flips through the later snapshots:
     * <ul>
     *   <li>our buy order fills from instant-sell flow (weekly volume / 168 per hour) times the analyzer's own
     *       competition share, but only while it is still the best buy order; when outbid it is repriced to the
     *       new top + 0.1 if the flip is still profitable, otherwise the rest is cancelled;</li>
     *   <li>bought units are offered one tick below the best sell offer; undercut offers are repriced the same way;</li>
     *   <li>whatever is still held when the data ends is sold instantly at the best buy order, after tax.</li>
     * </ul>
     * Price moves, undercutting and spreads are real; only the share of the flow we capture is modelled.
     */
    @Test
    void bazaarReplay() throws Exception {
        List<BazaarSnapshot> books = loadBazaar();
        ItemCatalog cat = catalog();
        SkyTradeConfig cfg = new SkyTradeConfig();
        double capital = 100_000_000;
        List<BzOutcome> out = new ArrayList<>();
        MarketHistory hist = new MarketHistory(2000, 0);
        long lastDecision = 0;
        for (int i = 0; i < books.size(); i++) {
            BazaarSnapshot snap = books.get(i);
            for (BazaarProduct p : snap.products().values()) {
                hist.record(p.id(), new PricePoint(snap.lastUpdated(), p.bestBid(), p.bestAsk(), p.bidDepth(), p.askDepth(),
                        p.quick().sellMovingWeek(), p.quick().buyMovingWeek()));
            }
            long remaining = books.get(books.size() - 1).lastUpdated() - snap.lastUpdated();
            if (snap.lastUpdated() - lastDecision < 10 * 60_000L || remaining < 30 * 60_000L || i < 5) continue;
            lastDecision = snap.lastUpdated();
            List<BazaarOpportunity> opps = new BazaarAnalyzer(cfg).analyze(snap, hist, cat, SizingContext.of(capital), snap.lastUpdated());
            int taken = 0;
            for (BazaarOpportunity o : opps) {
                if (taken >= 10) break;
                if (o.quantity() <= 0 || o.netProfit() < cfg.risk.minNetProfit || o.confidence() < cfg.risk.minConfidence) continue;
                taken++;
                out.add(follow(o, books, i, cfg));
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("snapshots", books.size());
        res.put("spanHours", books.isEmpty() ? 0 : (books.get(books.size() - 1).lastUpdated() - books.get(0).lastUpdated()) / 3.6e6);
        res.put("flips", out);
        write("bazaar_replay", res);
    }

    /**
     * The recording keeps only 5 levels per side, which undercounts competing orders. 14 is the measured median
     * near the top for the 50 most traded items on a full book.
     */
    static final int MIN_COMPETITORS = 14;

    static BzOutcome follow(BazaarOpportunity o, List<BazaarSnapshot> books, int start, SkyTradeConfig cfg) {
        double tax = cfg.fees.bazaarTax;
        double entry = o.entryPrice(), exit = o.exitPrice();
        double bought = 0, sold = 0, cost = 0, revenue = 0;
        boolean buying = true;
        int rb = 0, rs = 0;
        long prev = books.get(start).lastUpdated();
        BazaarProduct last = books.get(start).get(o.itemId());
        long maxHoldMs = (long) (cfg.risk.maxHoldingHours * 3.6e6);
        for (int k = start + 1; k < books.size(); k++) {
            BazaarSnapshot s = books.get(k);
            if (s.lastUpdated() - books.get(start).lastUpdated() > maxHoldMs) break;
            BazaarProduct p = s.get(o.itemId());
            if (p == null) continue;
            last = p;
            double dtH = (s.lastUpdated() - prev) / 3.6e6;
            prev = s.lastUpdated();
            if (dtH <= 0) continue;
            if (buying && bought < o.quantity()) {
                if (p.bestBid() > entry + 1e-9) {
                    double ne = Math.round((p.bestBid() + 0.1) * 10) / 10.0;
                    if ((p.bestAsk() - 0.1) * (1 - tax) - ne > 0) {
                        entry = ne;
                        rb++;
                    } else {
                        buying = false;
                    }
                }
                if (buying) {
                    double share = OrderBook.captureShare(Math.max(MIN_COMPETITORS, OrderBook.competitorsNear(p.bids(), 0.005)));
                    double fill = Math.min(o.quantity() - bought, p.instantSellsPerHour() * dtH * share);
                    bought += fill;
                    cost += fill * entry;
                }
            }
            double holding = bought - sold;
            if (holding > 1e-9) {
                if (p.bestAsk() < exit - 1e-9) {
                    exit = Math.round((p.bestAsk() - 0.1) * 10) / 10.0;
                    rs++;
                }
                double share = OrderBook.captureShare(Math.max(MIN_COMPETITORS, OrderBook.competitorsNear(p.asks(), 0.005)));
                double fill = Math.min(holding, p.instantBuysPerHour() * dtH * share);
                sold += fill;
                revenue += fill * exit * (1 - tax);
            }
            if (bought >= o.quantity() - 1e-9 && sold >= bought - 1e-9) break;
        }
        double held = bought - sold;
        double liquidation = held > 0 && last != null ? held * last.bestBid() * (1 - tax) : 0;
        double realized = revenue + liquidation - cost;
        return new BzOutcome(o.itemId(), books.get(start).lastUpdated(), o.quantity(), o.entryPrice(), o.exitPrice(), o.netProfit(),
                o.holdHours(), bought, sold, cost, revenue, liquidation, realized, rb, rs, o.risk(), o.confidence(), o.flags());
    }

    // ------------------------------------------------------------------ bazaar: 30 days of Coflnet history

    @Test
    void bazaarHistoryBacktest() throws Exception {
        Path p = DATA.resolve("coflnet_bazaar_history.json.gz");
        if (!Files.exists(p)) return;
        JsonObject root;
        try (BufferedReader r = gz(p)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        List<Map.Entry<String, PricePoint>> points = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonObject item = e.getValue().getAsJsonObject();
            Set<Long> seen = new HashSet<>();
            for (String k : List.of("week", "month")) {
                JsonElement arr = item.get(k);
                if (arr == null || !arr.isJsonArray()) continue;
                for (JsonElement el : arr.getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    long ts = LocalDateTime.parse(o.get("timestamp").getAsString()).toInstant(ZoneOffset.UTC).toEpochMilli();
                    if (!seen.add(ts)) continue;
                    // Coflnet copies Hypixel's quick status: "sell" = best buy order, "buy" = best sell offer.
                    points.add(Map.entry(e.getKey(), new PricePoint(ts, o.get("sell").getAsDouble(), o.get("buy").getAsDouble(),
                            o.get("sellVolume").getAsDouble(), o.get("buyVolume").getAsDouble(),
                            o.get("sellMovingWeek").getAsDouble(), o.get("buyMovingWeek").getAsDouble())));
                }
            }
        }
        points.sort(Comparator.comparingLong(x -> x.getValue().timestamp()));
        if (points.isEmpty()) return;
        long from = points.get(0).getValue().timestamp(), to = points.get(points.size() - 1).getValue().timestamp();
        SkyTradeConfig cfg = new SkyTradeConfig();
        cfg.api.staleAfterSeconds = 3 * 86_400;
        List<Backtester.Result> res = Backtester.compare(cfg, (a, b) -> {
            List<Map.Entry<String, PricePoint>> out = new ArrayList<>();
            for (var x : points) if (x.getValue().timestamp() >= a && x.getValue().timestamp() <= b) out.add(x);
            return out;
        }, catalog(), from, to, List.of(StrategyPreset.CONSERVATIVE, StrategyPreset.BALANCED, StrategyPreset.AGGRESSIVE), () -> false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", root.keySet());
        out.put("fromTs", from);
        out.put("toTs", to);
        out.put("points", points.size());
        out.put("results", res);
        write("bazaar_history", out);
    }
}
