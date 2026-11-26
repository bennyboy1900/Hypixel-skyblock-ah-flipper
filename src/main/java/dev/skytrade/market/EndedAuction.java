package dev.skytrade.market;

/** A completed sale from auctions_ended (last ~60 seconds of sales). */
public record EndedAuction(String uuid, long price, boolean bin, long timestamp, String itemBytes) {}
