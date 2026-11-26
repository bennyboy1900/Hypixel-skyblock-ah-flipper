package dev.skytrade.auction;

import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Normalized fingerprints for finding comparable items at three strictness levels.
 * <ul>
 *   <li>{@link #strict}: every value-relevant upgrade must match.</li>
 *   <li>{@link #relaxed}: identity plus the most value-dominant traits (rarity, stars, pet level bucket).</li>
 *   <li>{@link #base}: the item identity (and pet rarity) only.</li>
 * </ul>
 */
public final class Fingerprint {
    /** Enchants that materially change price regardless of level. */
    private static final Set<String> NOTABLE = Set.of("legion", "overload", "chimera", "soul_eater", "fatal_tempo",
            "wisdom", "last_stand", "bank", "combo", "inferno", "duplex", "one_for_all", "swarm", "rend", "habanero_tactics",
            "flash", "refrigerate", "bobbin_time", "the_one", "divine_gift", "pesterminator", "dedication", "tabasco",
            "strong_mana", "ferocious_mana", "hardened_mana", "mana_vampire", "smoldering", "toxophilite", "green_thumb",
            "ice_cold", "sunder", "prosecute", "cayenne");
    /** Enchant levels at/above which a regular enchant is considered a valuable book. */
    private static final int VALUABLE_LEVEL = 6;

    private Fingerprint() {}

    public static String base(ItemAttributes a) {
        return a.isPet() ? a.skyblockId() + "|" + a.rarity() : a.skyblockId();
    }

    public static String relaxed(ItemAttributes a) {
        StringJoiner j = new StringJoiner("|");
        j.add(a.skyblockId()).add(a.rarity());
        if (a.isPet()) j.add("lvl" + PetLevels.bucket(a.petLevel()));
        else j.add("s" + (a.dungeonStars() + a.masterStars()));
        if (!a.attributes().isEmpty()) j.add("attr" + attributeKey(a.attributes()));
        return j.toString();
    }

    public static String strict(ItemAttributes a) {
        StringJoiner j = new StringJoiner("|");
        j.add(relaxed(a));
        j.add(a.recombobulated() ? "R" : "-");
        if (a.isPet()) {
            j.add("item=" + (a.petHeldItem() == null ? "-" : a.petHeldItem()));
            j.add("skin=" + (a.skin() == null ? "-" : a.skin()));
            if (a.petCandyUsed() > 0) j.add("candy");
            return j.toString();
        }
        if (a.ultimateEnchant() != null) j.add(a.ultimateEnchant() + a.ultimateLevel());
        for (Map.Entry<String, Integer> e : a.enchantments().entrySet()) {
            if (e.getKey().startsWith("ultimate_")) continue;
            if (NOTABLE.contains(e.getKey()) || e.getValue() >= VALUABLE_LEVEL) j.add(e.getKey() + e.getValue());
        }
        if (a.hotPotatoBooks() > 0) j.add(a.hotPotatoBooks() >= 10 ? "hpb10" : "hpbp"); // coarse bucket: none/partial/full
        if (a.fumingPotatoBooks() > 0) j.add("fpb" + a.fumingPotatoBooks());
        if (!a.abilityScrolls().isEmpty()) j.add("scrolls" + a.abilityScrolls().size());
        if (a.skin() != null) j.add("skin=" + a.skin());
        long perfect = a.gems().values().stream().filter(v -> v.startsWith("PERFECT")).count();
        long flawless = a.gems().values().stream().filter(v -> v.startsWith("FLAWLESS")).count();
        if (perfect + flawless > 0) j.add("gems" + perfect + "p" + flawless + "f");
        return j.toString();
    }

    private static String attributeKey(Map<String, Integer> attrs) {
        StringJoiner j = new StringJoiner(",");
        attrs.forEach((k, v) -> j.add(k + v));
        return j.toString();
    }
}
