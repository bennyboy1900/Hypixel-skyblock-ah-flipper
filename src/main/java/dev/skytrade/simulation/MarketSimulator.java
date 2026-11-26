package dev.skytrade.simulation;

import dev.skytrade.market.AuctionSnapshot;
import dev.skytrade.market.ActiveAuction;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.util.TimeSource;

import java.util.*;
import java.util.function.Consumer;

/**
 * Local simulated market. Matches resting simulated orders against public market snapshots; it never
 * sends anything anywhere. Deterministic for a given seed and sequence of snapshots.
 */
public final class MarketSimulator {
    private final TimeSource time;
    private final long seed;
    private final Map<String, SimOrder> orders = new LinkedHashMap<>();
    private final Map<String, String> byClientId = new HashMap<>();
    private final Consumer<SimOrder> persist;
    private volatile BazaarSnapshot bazaar;
    private volatile Set<String> liveAuctions = Set.of();
    private volatile Map<String, Long> auctionPrices = Map.of();
    private long lastBazaarTick;
    private long lastAuctionTick;
    private double failureRate;
    private long counter;

    public MarketSimulator(TimeSource time, long seed, double failureRate, Consumer<SimOrder> persist) {
        this.time = time;
        this.seed = seed;
        this.failureRate = failureRate;
        this.persist = persist == null ? o -> { } : persist;
    }

    public synchronized void setFailureRate(double r) {
        failureRate = r;
    }

    public synchronized void restore(Collection<SimOrder> saved) {
        for (SimOrder o : saved) {
            orders.put(o.id, o);
            if (o.clientId != null) byClientId.put(o.clientId, o.id);
        }
    }

    public synchronized void clear() {
        orders.clear();
        byClientId.clear();
    }

    public BazaarSnapshot bazaar() {
        return bazaar;
    }

    private SplittableRandom rng(String id, long salt) {
        return new SplittableRandom(seed ^ (id.hashCode() * 0x9E3779B97F4A7C15L) ^ (salt * 0xC2B2AE3D27D4EB4FL));
    }

    // ---------------- order entry ----------------

    public synchronized SimOrder existing(String clientId) {
        String id = byClientId.get(clientId);
        return id == null ? null : orders.get(id);
    }

    public synchronized SimOrder place(String clientId, SimOrder.Type type, String itemId, long qty, double price, double salesPerDay, double durationHours) {
        SimOrder ex = existing(clientId);
        if (ex != null) return ex; // idempotent
        SimOrder o = new SimOrder();
        o.id = "sim-" + Long.toHexString(seed & 0xffff) + "-" + (++counter) + "-" + Long.toHexString(time.now());
        o.clientId = clientId;
        o.type = type;
        o.itemId = itemId;
        o.quantity = qty;
        o.price = price;
        o.createdAt = time.now();
        o.salesPerDay = salesPerDay;
        o.expiresAt = durationHours > 0 ? o.createdAt + (long) (durationHours * 3_600_000) : 0;
        if (rng(clientId, 0).nextDouble() < failureRate) {
            o.state = "REJECTED";
            o.message = "simulated transaction failure";
        } else if (type != SimOrder.Type.AUCTION_LISTING) {
            BazaarSnapshot bz = bazaar;
            if (bz == null || bz.get(itemId) == null) {
                o.state = "REJECTED";
                o.message = "unknown product";
            }
        }
        orders.put(o.id, o);
        byClientId.put(clientId, o.id);
        persist.accept(o);
        return o;
    }

    /** Attempts to buy a BIN listing. Success requires the listing to still exist and not be sniped first. */
    public synchronized SimOrder buyAuction(String clientId, String uuid, double price) {
        SimOrder ex = existing(clientId);
        if (ex != null) return ex;
        SimOrder o = new SimOrder();
        o.id = "simah-" + (++counter) + "-" + Long.toHexString(time.now());
        o.clientId = clientId;
        o.type = SimOrder.Type.AUCTION_LISTING;
        o.itemId = uuid;
        o.quantity = 1;
        o.price = price;
        o.createdAt = time.now();
        SplittableRandom r = rng(clientId, 1);
        Long current = auctionPrices.get(uuid);
        if (!liveAuctions.contains(uuid) || current == null) {
            o.state = "REJECTED";
            o.message = "listing no longer available";
        } else if (current > price) {
            o.state = "REJECTED";
            o.message = "price changed";
        } else if (r.nextDouble() < failureRate) {
            o.state = "REJECTED";
            o.message = "simulated transaction failure";
        } else if (r.nextDouble() < 0.35) {
            // Underpriced BINs are contested; assume a meaningful chance another player gets there first.
            o.state = "REJECTED";
            o.message = "sniped by another buyer";
        } else {
            o.state = "FILLED";
            o.filled = 1;
        }
        orders.put(o.id, o);
        byClientId.put(clientId, o.id);
        persist.accept(o);
        return o;
    }

    public synchronized SimOrder get(String id) {
        return orders.get(id);
    }

    public synchronized SimOrder cancel(String id) {
        SimOrder o = orders.get(id);
        if (o == null) return null;
        if (o.open()) {
            o.state = "CANCELLED";
            persist.accept(o);
        }
        return o;
    }

    public synchronized List<SimOrder> openOrders() {
        List<SimOrder> out = new ArrayList<>();
        for (SimOrder o : orders.values()) if (o.open()) out.add(o);
        return out;
    }

    /** Drops finished orders older than {@code maxAgeMs} to bound memory. */
    public synchronized void prune(long maxAgeMs) {
        long now = time.now();
        orders.values().removeIf(o -> !o.open() && now - o.createdAt > maxAgeMs);
        byClientId.values().removeIf(id -> !orders.containsKey(id));
    }

    // ---------------- matching ----------------

    public synchronized void onBazaar(BazaarSnapshot snap) {
        long t = snap.lastUpdated();
        if (bazaar != null && t <= lastBazaarTick) {
            bazaar = snap;
            return;
        }
        double hours = lastBazaarTick == 0 ? 0 : Math.min(10, (t - lastBazaarTick) / 60_000.0) / 60.0;
        lastBazaarTick = t;
        bazaar = snap;
        if (hours <= 0) return;
        for (SimOrder o : orders.values()) {
            if (!o.open() || o.type == SimOrder.Type.AUCTION_LISTING) continue;
            BazaarProduct p = snap.get(o.itemId);
            if (p == null) continue;
            double f = FillModel.fill(o, p, hours, rng(o.id, t)) + o.fillCarry;
            long whole = (long) Math.floor(f);
            o.fillCarry = f - whole;
            if (whole > 0) {
                o.filled = Math.min(o.quantity, o.filled + whole);
                if (o.filled >= o.quantity) o.state = "FILLED";
                persist.accept(o);
            }
        }
    }

    public synchronized void onAuctions(AuctionSnapshot snap) {
        Set<String> live = new HashSet<>(snap.auctions().size() * 2);
        Map<String, Long> prices = new HashMap<>(snap.auctions().size() * 2);
        for (ActiveAuction a : snap.auctions()) {
            if (!a.bin()) continue;
            live.add(a.uuid());
            prices.put(a.uuid(), a.startingBid());
        }
        liveAuctions = live;
        auctionPrices = prices;
    }

    /** Advances time-based processes (AH listing sales and expiry). Call periodically. */
    public synchronized void tickAuctions() {
        long now = time.now();
        if (lastAuctionTick == 0) {
            lastAuctionTick = now;
            return;
        }
        double hours = Math.min(1, (now - lastAuctionTick) / 3_600_000.0);
        lastAuctionTick = now;
        for (SimOrder o : orders.values()) {
            if (!o.open() || o.type != SimOrder.Type.AUCTION_LISTING || o.salesPerDay <= 0) continue;
            if (o.expiresAt > 0 && now > o.expiresAt) {
                o.state = "EXPIRED";
                persist.accept(o);
                continue;
            }
            double p = FillModel.auctionSaleProbability(o.salesPerDay, 0, hours);
            if (rng(o.id, now / 60_000).nextDouble() < p) {
                o.filled = 1;
                o.state = "FILLED";
                persist.accept(o);
            }
        }
    }
}
