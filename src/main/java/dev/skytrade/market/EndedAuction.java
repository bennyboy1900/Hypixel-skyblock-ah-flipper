package dev.skytrade.market;

/**
 * A completed sale from auctions_ended (last ~60 seconds of sales).
 * Seller and buyer are player UUIDs, kept only to detect wash trading between the same accounts.
 */
public record EndedAuction(String uuid, long price, boolean bin, long timestamp, String itemBytes, String seller, String buyer) {
    public EndedAuction(String uuid, long price, boolean bin, long timestamp, String itemBytes) {
        this(uuid, price, bin, timestamp, itemBytes, null, null);
    }
}
