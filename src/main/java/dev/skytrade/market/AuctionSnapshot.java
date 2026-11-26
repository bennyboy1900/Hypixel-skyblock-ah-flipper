package dev.skytrade.market;

import java.util.List;

/**
 * A full, deduplicated scan of active auctions taken against a single {@code lastUpdated} value.
 * {@code complete} is false if some pages could not be fetched consistently.
 */
public record AuctionSnapshot(long lastUpdated, long fetchedAt, List<ActiveAuction> auctions,
                              int totalAuctions, int pages, boolean complete) {
    public static final AuctionSnapshot EMPTY = new AuctionSnapshot(0, 0, List.of(), 0, 0, false);
}
