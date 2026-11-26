package dev.skytrade.auction;

import dev.skytrade.market.ActiveAuction;

/** An active listing joined with its decoded attributes and fingerprints. */
public record DecodedAuction(ActiveAuction auction, ItemAttributes attrs, String strict, String relaxed, String base) {}
