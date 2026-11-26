package dev.skytrade.market;

import com.google.gson.JsonObject;
import dev.skytrade.api.*;
import dev.skytrade.auction.Fingerprint;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.auction.ItemDecoder;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.util.Log;
import dev.skytrade.util.TimeSource;
import dev.skytrade.valuation.SaleRecord;
import dev.skytrade.valuation.SalesHistory;
import dev.skytrade.valuation.UpgradeComponent;
import dev.skytrade.valuation.UpgradeValuer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Centralised market data cache. Polls each endpoint on its own cadence, skips datasets whose
 * {@code lastUpdated} has not changed (no duplicate processing), and publishes immutable snapshots.
 */
public final class MarketDataService {
    private final HypixelApiClient api;
    private final Supplier<SkyTradeConfig> config;
    private final TimeSource time;
    private final ItemDecoder decoder;
    private final SalesHistory sales;
    private final MarketHistory history;

    private volatile BazaarSnapshot bazaar;
    private volatile AuctionSnapshot auctions = AuctionSnapshot.EMPTY;
    private volatile ItemCatalog catalog = ItemCatalog.EMPTY;
    private volatile long lastEndedFetch;
    private volatile String auctionScanProgress = "";

    private final AtomicBoolean bazaarBusy = new AtomicBoolean();
    private final AtomicBoolean auctionBusy = new AtomicBoolean();
    private final AtomicBoolean endedBusy = new AtomicBoolean();
    private final AtomicBoolean itemsBusy = new AtomicBoolean();

    private final List<Consumer<BazaarSnapshot>> bazaarListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<AuctionSnapshot>> auctionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<SaleRecord>>> salesListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Map<String, PricePoint>>> historyListeners = new CopyOnWriteArrayList<>();

    public MarketDataService(HypixelApiClient api, Supplier<SkyTradeConfig> config, TimeSource time, ItemDecoder decoder,
                             SalesHistory sales, MarketHistory history) {
        this.api = api;
        this.config = config;
        this.time = time;
        this.decoder = decoder;
        this.sales = sales;
        this.history = history;
    }

    public void onBazaar(Consumer<BazaarSnapshot> l) { bazaarListeners.add(l); }
    public void onAuctions(Consumer<AuctionSnapshot> l) { auctionListeners.add(l); }
    public void onSales(Consumer<List<SaleRecord>> l) { salesListeners.add(l); }
    public void onHistory(Consumer<Map<String, PricePoint>> l) { historyListeners.add(l); }

    public BazaarSnapshot bazaar() { return bazaar; }
    public AuctionSnapshot auctions() { return auctions; }
    public ItemCatalog catalog() { return catalog; }
    public SalesHistory sales() { return sales; }
    public MarketHistory history() { return history; }
    public ItemDecoder decoder() { return decoder; }
    public ApiStatus status() { return api.status(); }
    public String auctionScanProgress() { return auctionScanProgress; }
    public long lastEndedFetch() { return lastEndedFetch; }

    public DataFreshness bazaarFreshness() {
        BazaarSnapshot b = bazaar;
        return DataFreshness.of(b == null ? 0 : b.lastUpdated(), time.now(), config.get().api.staleAfterSeconds * 1000L);
    }

    public long bazaarAgeMs() {
        BazaarSnapshot b = bazaar;
        return b == null ? Long.MAX_VALUE / 4 : b.ageMs(time.now());
    }

    // ------------------------------------------------------------------ bazaar

    public void pollBazaar() {
        if (!api.ready(Endpoint.BAZAAR) || !bazaarBusy.compareAndSet(false, true)) return;
        try {
            long fetched = time.now();
            JsonObject json = api.fetch(Endpoint.BAZAAR, null);
            BazaarSnapshot snap = ApiParsers.bazaar(json, fetched);
            BazaarSnapshot prev = bazaar;
            if (prev != null && snap.lastUpdated() <= prev.lastUpdated()) {
                Log.debug("bazaar.unchanged", "lastUpdated", snap.lastUpdated());
                return; // duplicate dataset: nothing to do
            }
            if (snap.products().isEmpty()) throw new ApiException("bazaar: empty product list", 200, true);
            bazaar = snap;
            Map<String, PricePoint> recorded = new HashMap<>();
            for (BazaarProduct p : snap.products().values()) {
                if (!p.hasBook()) continue;
                PricePoint pt = new PricePoint(snap.lastUpdated(), p.bestBid(), p.bestAsk(), p.bidDepth(), p.askDepth(),
                        p.quick().sellMovingWeek(), p.quick().buyMovingWeek());
                if (history.record(p.id(), pt)) recorded.put(p.id(), pt);
            }
            Log.info("bazaar.updated", "products", snap.products().size(), "ageMs", snap.ageMs(time.now()), "historyPoints", recorded.size());
            for (Consumer<Map<String, PricePoint>> l : historyListeners) l.accept(recorded);
            for (Consumer<BazaarSnapshot> l : bazaarListeners) l.accept(snap);
        } catch (ApiException e) {
            // already recorded in ApiStatus with backoff
        } finally {
            bazaarBusy.set(false);
        }
    }

    // ------------------------------------------------------------------ auctions

    /**
     * Full scan of active auctions that yields one consistent snapshot. A scan can straddle the API's
     * periodic refresh, so pages may report different {@code lastUpdated} values. Instead of mixing
     * snapshots, the scan adopts the newest {@code lastUpdated} seen and re-fetches only the pages that
     * are still on an older snapshot (up to three repair rounds). Listings are deduplicated by UUID.
     */
    public void pollAuctions() {
        SkyTradeConfig.Api cfg = config.get().api;
        if (!cfg.scanAuctions || !api.ready(Endpoint.AUCTIONS) || !auctionBusy.compareAndSet(false, true)) return;
        try {
            long fetched = time.now();
            ApiParsers.AuctionPage first = ApiParsers.auctionPage(api.fetch(Endpoint.AUCTIONS, "page=0"));
            if (first.lastUpdated() <= auctions.lastUpdated()) return; // nothing new
            Map<Integer, ApiParsers.AuctionPage> pages = new HashMap<>();
            pages.put(0, first);
            int total = clampPages(first.totalPages());
            List<Integer> todo = new ArrayList<>();
            for (int i = 1; i < total; i++) todo.add(i);
            int repairs = 0;
            while (true) {
                pages.putAll(fetchPages(todo, cfg, total));
                long newest = 0;
                int newestTotal = total;
                for (ApiParsers.AuctionPage p : pages.values()) {
                    if (p.lastUpdated() > newest) {
                        newest = p.lastUpdated();
                        newestTotal = clampPages(p.totalPages());
                    }
                }
                total = newestTotal;
                final long target = newest;
                final int t = total;
                pages.keySet().removeIf(k -> k >= t);
                todo = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    ApiParsers.AuctionPage p = pages.get(i);
                    if (p == null || p.lastUpdated() != target) todo.add(i);
                }
                if (todo.isEmpty()) break;
                if (repairs++ >= 3) break;
                auctionScanProgress = "repairing " + todo.size() + " pages (snapshot changed during scan)";
                Log.debug("auctions.scan_repair", "pages", todo.size(), "lastUpdated", target);
            }
            long target = 0;
            for (ApiParsers.AuctionPage p : pages.values()) target = Math.max(target, p.lastUpdated());
            int consistent = 0, totalAuctions = 0;
            Map<String, ActiveAuction> merged = new LinkedHashMap<>(first.totalAuctions() * 2 + 16);
            for (int i = 0; i < total; i++) {
                ApiParsers.AuctionPage p = pages.get(i);
                if (p == null || p.lastUpdated() != target) continue;
                consistent++;
                totalAuctions = p.totalAuctions();
                for (ActiveAuction a : p.auctions()) merged.putIfAbsent(a.uuid(), a);
            }
            int missing = total - consistent;
            if (missing > Math.max(1, total / 20)) {
                Log.warn("auctions.scan_incomplete", "missingPages", missing, "total", total);
                auctionScanProgress = missing + " of " + total + " pages unavailable; retrying next cycle";
                return;
            }
            AuctionSnapshot snap = new AuctionSnapshot(target, fetched, List.copyOf(merged.values()), totalAuctions, total, missing == 0);
            auctions = snap;
            auctionScanProgress = "";
            Log.info("auctions.updated", "auctions", merged.size(), "pages", total, "complete", missing == 0, "repairRounds", repairs,
                    "ms", time.now() - fetched, "decodeCache", decoder.cacheSize());
            for (Consumer<AuctionSnapshot> l : auctionListeners) l.accept(snap);
        } catch (ApiException e) {
            auctionScanProgress = "error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            auctionBusy.set(false);
        }
    }

    private static int clampPages(int n) {
        return Math.max(1, Math.min(n, 200));
    }

    /** Fetches the given pages with bounded concurrency; failed pages are simply absent from the result. */
    private Map<Integer, ApiParsers.AuctionPage> fetchPages(List<Integer> pageNumbers, SkyTradeConfig.Api cfg, int total) throws InterruptedException {
        Map<Integer, ApiParsers.AuctionPage> out = new HashMap<>();
        Semaphore permits = new Semaphore(cfg.auctionPageConcurrency);
        Map<Integer, CompletableFuture<ApiParsers.AuctionPage>> futures = new LinkedHashMap<>();
        int done = 0;
        for (int page : pageNumbers) {
            permits.acquire();
            futures.put(page, api.fetchAsync(Endpoint.AUCTIONS, "page=" + page)
                    .thenApply(ApiParsers::auctionPage)
                    .whenComplete((r, t) -> permits.release()));
            auctionScanProgress = "page " + (++done) + "/" + pageNumbers.size() + " of " + total;
        }
        for (Map.Entry<Integer, CompletableFuture<ApiParsers.AuctionPage>> e : futures.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().get(cfg.requestTimeoutMs + 30_000L, TimeUnit.MILLISECONDS));
            } catch (ExecutionException | TimeoutException ex) {
                // missing page: retried by the repair loop
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ended auctions

    public void pollEnded() {
        if (!api.ready(Endpoint.AUCTIONS_ENDED) || !endedBusy.compareAndSet(false, true)) return;
        try {
            List<EndedAuction> ended = ApiParsers.ended(api.fetch(Endpoint.AUCTIONS_ENDED, null));
            lastEndedFetch = time.now();
            List<SaleRecord> fresh = new ArrayList<>();
            for (EndedAuction e : ended) {
                Optional<ItemAttributes> a = decoder.decode("ended:" + e.uuid(), e.itemBytes(), null);
                if (a.isEmpty() || a.get().count() != 1) continue;
                ItemAttributes at = a.get();
                List<String> ups = new ArrayList<>();
                for (UpgradeComponent c : UpgradeValuer.components(at)) ups.add(c.encode());
                SaleRecord s = new SaleRecord(e.uuid(), e.timestamp(), e.price(), e.bin(), at.skyblockId(),
                        Fingerprint.base(at), Fingerprint.relaxed(at), Fingerprint.strict(at), List.copyOf(ups));
                if (sales.add(s)) fresh.add(s);
            }
            if (!fresh.isEmpty()) {
                Log.debug("ended.updated", "new", fresh.size());
                for (Consumer<List<SaleRecord>> l : salesListeners) l.accept(fresh);
            }
        } catch (ApiException e) {
            // tracked in status
        } finally {
            endedBusy.set(false);
        }
    }

    // ------------------------------------------------------------------ items

    public void refreshItems() {
        if (!api.ready(Endpoint.ITEMS) || !itemsBusy.compareAndSet(false, true)) return;
        try {
            ItemCatalog c = ApiParsers.items(api.fetch(Endpoint.ITEMS, null), time.now());
            if (!c.items().isEmpty()) {
                catalog = c;
                Log.info("items.updated", "items", c.items().size());
            }
        } catch (ApiException e) {
            // tracked in status
        } finally {
            itemsBusy.set(false);
        }
    }

    /** Lets an offline replay inject snapshots (used by tests/backtests). */
    public void inject(BazaarSnapshot snap) {
        bazaar = snap;
        for (Consumer<BazaarSnapshot> l : bazaarListeners) l.accept(snap);
    }
}
