package dev.skytrade.valuation;

import java.util.List;

/** A completed auction sale reduced to the fields needed for valuation (no raw item data retained). */
public record SaleRecord(String uuid, long timestamp, long price, boolean bin, String itemId, String base,
                         String relaxed, String strict, List<String> upgrades) {}
