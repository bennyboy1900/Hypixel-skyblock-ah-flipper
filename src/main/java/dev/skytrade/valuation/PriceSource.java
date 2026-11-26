package dev.skytrade.valuation;

/** Conservative unit price lookup for upgrade components / ingredients. NaN when unknown. */
@FunctionalInterface
public interface PriceSource {
    double price(String itemId);
}
