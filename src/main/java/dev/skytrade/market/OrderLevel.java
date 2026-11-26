package dev.skytrade.market;

/** One price level of a bazaar order book. */
public record OrderLevel(double price, long amount, int orders) {}
