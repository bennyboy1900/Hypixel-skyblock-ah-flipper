package dev.skytrade.auction;

import dev.skytrade.market.ActiveAuction;
import dev.skytrade.market.AuctionSnapshot;

import java.util.*;

/** Per-snapshot index of active BIN listings grouped by fingerprint. Immutable once built. */
public final class AuctionIndex {
    public static final AuctionIndex EMPTY = new AuctionIndex(0, Map.of(), Map.of(), 0, 0);

    private final long lastUpdated;
    private final Map<String, List<DecodedAuction>> byStrict;
    private final Map<String, Long> lowestCleanBinByBase;
    private final int decoded;
    private final int failed;

    private AuctionIndex(long lastUpdated, Map<String, List<DecodedAuction>> byStrict, Map<String, Long> lowest, int decoded, int failed) {
        this.lastUpdated = lastUpdated;
        this.byStrict = byStrict;
        this.lowestCleanBinByBase = lowest;
        this.decoded = decoded;
        this.failed = failed;
    }

    public static AuctionIndex build(AuctionSnapshot snap, ItemDecoder decoder) {
        Map<String, List<DecodedAuction>> byStrict = new HashMap<>();
        Map<String, Long> lowest = new HashMap<>();
        int ok = 0, bad = 0;
        for (ActiveAuction a : snap.auctions()) {
            if (!a.bin()) continue;
            Optional<ItemAttributes> attrs = decoder.decode(a.uuid(), a.itemBytes(), a.tier());
            if (attrs.isEmpty()) {
                bad++;
                continue;
            }
            ok++;
            ItemAttributes at = attrs.get();
            if (at.count() != 1) continue; // stacks are not comparable per-unit here
            DecodedAuction d = new DecodedAuction(a, at, Fingerprint.strict(at), Fingerprint.relaxed(at), Fingerprint.base(at));
            byStrict.computeIfAbsent(d.strict(), k -> new ArrayList<>()).add(d);
            if (isClean(at)) lowest.merge(at.skyblockId(), a.startingBid(), Math::min);
        }
        for (List<DecodedAuction> l : byStrict.values()) l.sort(Comparator.comparingLong(x -> x.auction().startingBid()));
        return new AuctionIndex(snap.lastUpdated(), Collections.unmodifiableMap(byStrict), Collections.unmodifiableMap(lowest), ok, bad);
    }

    static boolean isClean(ItemAttributes a) {
        return !a.recombobulated() && a.hotPotatoBooks() == 0 && a.abilityScrolls().isEmpty() && a.gems().isEmpty()
                && a.dungeonStars() == 0 && a.ultimateEnchant() == null && a.skin() == null;
    }

    public Map<String, List<DecodedAuction>> groups() {
        return byStrict;
    }

    public List<DecodedAuction> group(String strict) {
        return byStrict.getOrDefault(strict, List.of());
    }

    /** Lowest BIN of an un-upgraded copy, used to price non-bazaar items such as scrolls and pet items. */
    public double lowestCleanBin(String id) {
        Long v = lowestCleanBinByBase.get(id);
        return v == null ? Double.NaN : v;
    }

    public long lastUpdated() {
        return lastUpdated;
    }

    public int decodedCount() {
        return decoded;
    }

    public int failedCount() {
        return failed;
    }
}
