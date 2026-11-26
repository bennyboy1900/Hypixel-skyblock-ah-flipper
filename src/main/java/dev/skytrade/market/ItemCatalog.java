package dev.skytrade.market;

import dev.skytrade.util.Fmt;

import java.util.Map;

/** Immutable item catalog. */
public record ItemCatalog(long lastUpdated, long fetchedAt, Map<String, SkyblockItem> items) {
    public static final ItemCatalog EMPTY = new ItemCatalog(0, 0, Map.of());

    public SkyblockItem get(String id) {
        return items.get(id);
    }

    public String displayName(String id) {
        SkyblockItem i = items.get(id);
        if (i != null && i.name() != null && !i.name().isEmpty()) return Fmt.stripFormatting(i.name());
        return Fmt.prettyId(id);
    }

    /** Coarse correlation group used for exposure limits. */
    public String group(String id) {
        SkyblockItem i = items.get(id);
        if (i != null && i.category() != null && !i.category().isEmpty()) return i.category();
        if (id.startsWith("ENCHANTED_")) return "ENCHANTED_" + id.substring(10).split("_")[0];
        int us = id.indexOf('_');
        return us > 0 ? id.substring(0, us) : id;
    }
}
