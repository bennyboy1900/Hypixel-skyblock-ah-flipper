package dev.skytrade.valuation;

import java.util.List;

/**
 * A completed auction sale reduced to the fields needed for valuation (no raw item data retained).
 * {@code seller}/{@code buyer} are player UUIDs (may be null for old records) used to filter wash trades.
 */
public record SaleRecord(String uuid, long timestamp, long price, boolean bin, String itemId, String base,
                         String relaxed, String strict, List<String> upgrades, String seller, String buyer) {
    /** Marker stored in {@link #upgrades} by records whose upgrade list is complete (all enchants, extras, slots). */
    public static final String FULL_UPGRADES = "~v2";

    public SaleRecord(String uuid, long timestamp, long price, boolean bin, String itemId, String base,
                      String relaxed, String strict, List<String> upgrades) {
        this(uuid, timestamp, price, bin, itemId, base, relaxed, strict, upgrades, null, null);
    }

    public boolean fullUpgrades() {
        return upgrades.contains(FULL_UPGRADES);
    }
}
