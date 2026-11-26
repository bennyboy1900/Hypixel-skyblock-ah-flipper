package dev.skytrade.market;

/** An active auction from the paginated auctions endpoint. */
public record ActiveAuction(String uuid, String auctioneer, String itemName, String tier, String category,
                            long startingBid, long highestBid, boolean bin, long start, long end,
                            String itemBytes, String lore) {
    public long price() {
        return bin ? startingBid : Math.max(startingBid, highestBid);
    }
}
