package dev.skytrade.auction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.LruCache;

import java.io.IOException;
import java.util.*;

/**
 * Decodes Base64/GZIP NBT {@code item_bytes} into {@link ItemAttributes}. Results are cached by
 * auction UUID (bounded LRU) so the same listing is never decoded twice across scans.
 */
public final class ItemDecoder {
    private static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL", "ULTIMATE", "ADMIN"};
    /** Integer counters in ExtraAttributes that record applied, purchasable upgrades: NBT key -> item id. */
    private static final Map<String, String> COUNTED_UPGRADES = Map.ofEntries(
            Map.entry("wood_singularity_count", "WOOD_SINGULARITY"),
            Map.entry("jalapeno_count", "JALAPENO_BOOK"),
            Map.entry("mana_disintegrator_count", "MANA_DISINTEGRATOR"),
            Map.entry("tuned_transmission", "TRANSMISSION_TUNER"),
            Map.entry("farming_for_dummies_count", "FARMING_FOR_DUMMIES"),
            Map.entry("polarvoid", "POLARVOID_BOOK"),
            Map.entry("ethermerge", "ETHERWARP_CONDUIT"),
            Map.entry("divan_powder_coating", "DIVAN_POWDER_COATING"),
            Map.entry("art_of_peace_applied", "THE_ART_OF_PEACE"),
            Map.entry("stats_book", "BOOK_OF_STATS"));
    /** String fields in ExtraAttributes whose value is (or maps to) the id of an applied upgrade item. */
    private static final List<String> NAMED_UPGRADES = List.of("power_ability_scroll", "dye_item",
            "drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module");
    private final LruCache<String, Optional<ItemAttributes>> cache;

    public ItemDecoder(int cacheSize) {
        this.cache = new LruCache<>(cacheSize);
    }

    public Optional<ItemAttributes> decode(String cacheKey, String itemBytes, String fallbackTier) {
        return cache.computeIfAbsent(cacheKey, k -> {
            try {
                return Optional.ofNullable(decodeUncached(itemBytes, fallbackTier));
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    public int cacheSize() {
        return cache.size();
    }

    public static ItemAttributes decodeUncached(String itemBytes, String fallbackTier) throws IOException {
        Map<String, Object> root = Nbt.readBase64Gzip(itemBytes);
        List<Object> items = Nbt.list(root, "i");
        if (items.isEmpty() || !(items.get(0) instanceof Map)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) items.get(0);
        Map<String, Object> tag = Nbt.compound(item, "tag");
        Map<String, Object> ea = Nbt.compound(tag, "ExtraAttributes");
        if (ea == null) return null;
        String id = Nbt.string(ea, "id", null);
        if (id == null) return null;
        int count = Nbt.integer(item, "Count", 1);

        Map<String, Object> display = Nbt.compound(tag, "display");
        String name = Fmt.stripFormatting(Nbt.string(display, "Name", id));
        List<Object> lore = Nbt.list(display, "Lore");
        String rarity = rarityFromLore(lore, fallbackTier);

        Map<String, Integer> ench = new TreeMap<>();
        String ultimate = null;
        int ultimateLevel = 0;
        Map<String, Object> enchTag = Nbt.compound(ea, "enchantments");
        if (enchTag != null) {
            for (Map.Entry<String, Object> e : enchTag.entrySet()) {
                if (!(e.getValue() instanceof Number n)) continue;
                String key = e.getKey().toLowerCase(Locale.ROOT);
                ench.put(key, n.intValue());
                if (key.startsWith("ultimate_")) {
                    ultimate = key;
                    ultimateLevel = n.intValue();
                }
            }
        }

        boolean recomb = Nbt.integer(ea, "rarity_upgrades", 0) > 0;
        int stars = Math.max(Nbt.integer(ea, "upgrade_level", 0), Nbt.integer(ea, "dungeon_item_level", 0));
        int dungeonStars = Math.min(5, stars);
        int masterStars = Math.max(0, Math.min(5, stars - 5));

        Map<String, String> gems = new TreeMap<>();
        List<String> unlockedSlots = new ArrayList<>();
        Map<String, Object> gemTag = Nbt.compound(ea, "gems");
        if (gemTag != null) {
            for (Object o : Nbt.list(gemTag, "unlocked_slots")) if (o instanceof String slot) unlockedSlots.add(slot);
            Collections.sort(unlockedSlots);
            for (Map.Entry<String, Object> e : gemTag.entrySet()) {
                String k = e.getKey();
                if (k.endsWith("_gem") || k.equals("unlocked_slots")) continue;
                String quality = null;
                if (e.getValue() instanceof String s) quality = s;
                else if (e.getValue() instanceof Map<?, ?> m && m.get("quality") instanceof String s) quality = s;
                if (quality == null) continue;
                String gemType = k.replaceAll("_\\d+$", "");
                Object explicit = gemTag.get(k + "_gem");
                if (explicit instanceof String s) gemType = s;
                gems.put(k, quality + "_" + gemType + "_GEM");
            }
        }

        int potato = Nbt.integer(ea, "hot_potato_count", 0);
        int hot = Math.min(10, potato);
        int fuming = Math.max(0, potato - 10);

        List<String> scrolls = new ArrayList<>();
        for (Object o : Nbt.list(ea, "ability_scroll")) if (o instanceof String s) scrolls.add(s);
        Collections.sort(scrolls);

        String petType = null, petItem = null, skin = Nbt.string(ea, "skin", null);
        int petLevel = 0, candy = 0;
        String petInfo = Nbt.string(ea, "petInfo", null);
        if ("PET".equals(id) && petInfo != null) {
            try {
                JsonObject p = JsonParser.parseString(petInfo).getAsJsonObject();
                petType = p.has("type") ? p.get("type").getAsString() : "UNKNOWN";
                if (p.has("tier")) rarity = p.get("tier").getAsString();
                // Tier boost raises rarity by one but does not change the pet's xp curve.
                if (p.has("heldItem") && !p.get("heldItem").isJsonNull()) petItem = p.get("heldItem").getAsString();
                if (p.has("skin") && !p.get("skin").isJsonNull()) skin = p.get("skin").getAsString();
                double exp = p.has("exp") ? p.get("exp").getAsDouble() : 0;
                candy = p.has("candyUsed") ? p.get("candyUsed").getAsInt() : 0;
                int max = "GOLDEN_DRAGON".equals(petType) ? 200 : 100;
                petLevel = PetLevels.level(rarity, exp, max);
                id = "PET_" + petType;
            } catch (RuntimeException ignored) {
                id = "PET_UNKNOWN";
            }
        }

        Map<String, Integer> attrs = new TreeMap<>();
        Map<String, Object> attrTag = Nbt.compound(ea, "attributes");
        if (attrTag != null) {
            for (Map.Entry<String, Object> e : attrTag.entrySet()) {
                if (e.getValue() instanceof Number n) attrs.put(e.getKey(), n.intValue());
            }
        }

        Map<String, Integer> extras = new TreeMap<>();
        for (Map.Entry<String, String> e : COUNTED_UPGRADES.entrySet()) {
            int n = Nbt.integer(ea, e.getKey(), 0);
            if (n > 0) extras.merge(e.getValue(), n, Integer::sum);
        }
        for (String key : NAMED_UPGRADES) {
            String v = Nbt.string(ea, key, null);
            if (v != null && !v.isBlank()) extras.merge(v.toUpperCase(Locale.ROOT), 1, Integer::sum);
        }
        String enrichment = Nbt.string(ea, "talisman_enrichment", null);
        if (enrichment != null && !enrichment.isBlank()) {
            extras.merge("TALISMAN_ENRICHMENT_" + enrichment.toUpperCase(Locale.ROOT), 1, Integer::sum);
        }

        return new ItemAttributes(id, rarity, Nbt.string(ea, "modifier", null), Collections.unmodifiableMap(ench),
                ultimate, ultimateLevel, recomb, dungeonStars, masterStars, Collections.unmodifiableMap(gems),
                hot, fuming, List.copyOf(scrolls), Nbt.integer(ea, "art_of_war_count", 0) > 0, petType, petLevel,
                petItem, candy, skin, Collections.unmodifiableMap(attrs), count, name,
                Collections.unmodifiableMap(extras), List.copyOf(unlockedSlots), variant(id, ea, ench));
    }

    /**
     * Identity qualifiers that make items with one id different products with very different prices.
     * Returns null for ordinary items.
     */
    public static String variant(String id, Map<String, Object> ea, Map<String, Integer> ench) {
        StringJoiner j = new StringJoiner(",");
        if ("ENCHANTED_BOOK".equals(id) && !ench.isEmpty()) ench.forEach((k, v) -> j.add(k + v)); // the book IS its enchants
        int cake = Nbt.integer(ea, "new_years_cake", -1);
        if (cake >= 0) j.add("year" + cake);
        Map<String, Object> runes = Nbt.compound(ea, "runes");
        if (runes != null) new TreeMap<>(runes).forEach((k, v) -> j.add("rune_" + k.toLowerCase(Locale.ROOT) + (v instanceof Number n ? n.intValue() : 0)));
        for (String key : List.of("party_hat_color", "party_hat_emoji", "potion", "potion_type")) {
            String v = Nbt.string(ea, key, null);
            if (v != null && !v.isBlank()) j.add(key + "=" + v.toLowerCase(Locale.ROOT));
        }
        int potionLevel = Nbt.integer(ea, "potion_level", 0);
        if (potionLevel > 0) j.add("plvl" + potionLevel);
        if (Nbt.integer(ea, "is_shiny", 0) > 0) j.add("shiny");
        return j.length() == 0 ? null : j.toString();
    }

    /** The last lore line carries the rarity (e.g. "§6§lLEGENDARY SWORD"). */
    static String rarityFromLore(List<Object> lore, String fallback) {
        for (int i = lore.size() - 1; i >= 0 && i >= lore.size() - 3; i--) {
            if (!(lore.get(i) instanceof String line)) continue;
            String clean = Fmt.stripFormatting(line).trim().toUpperCase(Locale.ROOT);
            if (clean.startsWith("A ")) clean = clean.substring(2); // "a SPECIAL" variants
            for (int r = RARITIES.length - 1; r >= 0; r--) {
                String rar = RARITIES[r].replace('_', ' ');
                if (clean.startsWith(rar)) return RARITIES[r];
            }
        }
        return fallback == null ? "COMMON" : fallback;
    }
}
