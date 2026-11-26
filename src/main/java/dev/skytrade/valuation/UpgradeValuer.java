package dev.skytrade.valuation;

import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.market.SkyblockItem;
import dev.skytrade.util.Stats;

import java.util.*;

/**
 * Extracts priceable upgrade components from an item and values them at a partial recovery rate.
 *
 * <p>Upgrades are never valued at full purchase price: buyers rarely pay full cost for applied upgrades.
 * How much is recovered depends on the kind of upgrade, so every component belongs to a {@link Kind}
 * whose weight scales the configured base recovery rate (55% by default). Removable or highly demanded
 * upgrades (gemstones, scrolls, master stars, pet items, recombobulators) recover far more than consumed
 * ones (potato books, reforge stones, slot unlocks).</p>
 *
 * <p>Every enchantment is extracted. Only books cheaper than the configured minimum (default
 * {@link #DEFAULT_MIN_ENCHANT_COST}) are ignored at valuation time, so comparables and listings are treated the
 * same way. A low threshold matters for cheap items: on a 1M item, a few 20-100k books are a large part of the price. If a book of the exact level has no price, it is derived from the highest priced lower level
 * (two books of level N combine into N+1), with a 20% haircut, because derived prices are less reliable.</p>
 */
public final class UpgradeValuer {
    /** Default: enchant books whose full cost is below this are ignored (they barely move any price). */
    public static final double DEFAULT_MIN_ENCHANT_COST = 5_000;
    /** Never credit more than this fraction of an upgrade's cost, whatever the weights say. */
    private static final double MAX_RECOVERY = 0.95;
    private static final double DERIVED_HAIRCUT = 0.8;
    private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};
    /** Prefix for consumed gemstone-slot unlock costs, so they are not confused with removable gems. */
    public static final String UNLOCK_PREFIX = "UNLOCK:";

    /** Reforges whose reforge stone is worth crediting. Reforge name (lower case) -> stone item id. */
    private static final Map<String, String> REFORGE_STONES = Map.ofEntries(
            Map.entry("fabled", "DRAGON_CLAW"),
            Map.entry("withered", "WITHER_BLOOD"),
            Map.entry("giant", "GIANT_TOOTH"),
            Map.entry("ancient", "PRECURSOR_GEAR"),
            Map.entry("renowned", "DRAGON_HORN"),
            Map.entry("spiritual", "SPIRIT_DECOY"),
            Map.entry("necrotic", "NECROMANCER_BROOCH"),
            Map.entry("submerged", "DEEP_SEA_ORB"),
            Map.entry("jaded", "JADERALD"),
            Map.entry("gilded", "MIDAS_JEWEL"),
            Map.entry("warped", "AOTE_STONE"),
            Map.entry("precise", "OPTICAL_LENS"));

    /** Upgrade kinds and their weight relative to the configured base recovery rate. */
    public enum Kind {
        RECOMBOBULATOR(1.45), HOT_POTATO(1.0), FUMING_POTATO(1.15), ART_OF_WAR(1.2), MASTER_STAR(1.5),
        SCROLL(1.6), GEMSTONE(1.6), SLOT_UNLOCK(0.8), ENCHANT(1.1), ULTIMATE_ENCHANT(1.3), PET_ITEM(1.6),
        REFORGE_STONE(0.5), DRILL_PART(1.5), OTHER(0.9);

        public final double weight;

        Kind(double weight) {
            this.weight = weight;
        }
    }

    private UpgradeValuer() {}

    /** Components without catalog data (no gemstone-slot unlock costs). */
    public static List<UpgradeComponent> components(ItemAttributes a) {
        return components(a, ItemCatalog.EMPTY);
    }

    public static List<UpgradeComponent> components(ItemAttributes a, ItemCatalog catalog) {
        List<UpgradeComponent> out = new ArrayList<>();
        if (a.recombobulated() && !"PET_UNKNOWN".equals(a.skyblockId())) out.add(new UpgradeComponent("RECOMBOBULATOR_3000", 1, "Recombobulator"));
        if (a.hotPotatoBooks() > 0) out.add(new UpgradeComponent("HOT_POTATO_BOOK", a.hotPotatoBooks(), "Hot Potato Books"));
        if (a.fumingPotatoBooks() > 0) out.add(new UpgradeComponent("FUMING_POTATO_BOOK", a.fumingPotatoBooks(), "Fuming Potato Books"));
        if (a.artOfWar()) out.add(new UpgradeComponent("THE_ART_OF_WAR", 1, "Art of War"));
        for (int i = 0; i < a.masterStars(); i++) out.add(new UpgradeComponent(MASTER_STARS[i], 1, "Master Star " + (i + 1)));
        for (String s : a.abilityScrolls()) out.add(new UpgradeComponent(s, 1, "Scroll"));
        Map<String, Integer> gems = new TreeMap<>();
        for (String g : a.gems().values()) gems.merge(g, 1, Integer::sum);
        gems.forEach((g, n) -> out.add(new UpgradeComponent(g, n, "Gemstone")));
        if (a.ultimateEnchant() != null) {
            out.add(new UpgradeComponent("ENCHANTMENT_" + a.ultimateEnchant().toUpperCase(Locale.ROOT) + "_" + a.ultimateLevel(), 1, "Ultimate enchant"));
        }
        for (Map.Entry<String, Integer> e : a.enchantments().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("ultimate_") || e.getValue() <= 0) continue;
            out.add(new UpgradeComponent("ENCHANTMENT_" + k.toUpperCase(Locale.ROOT) + "_" + e.getValue(), 1, "Enchant book"));
        }
        if (a.isPet() && a.petHeldItem() != null) out.add(new UpgradeComponent(a.petHeldItem(), 1, "Pet item"));
        if (a.extras() != null) {
            a.extras().forEach((id, n) -> out.add(new UpgradeComponent(id, n, id.startsWith("DRILL_PART") || isDrillPart(id) ? "Drill part" : "Upgrade")));
        }
        if (a.reforge() != null) {
            String stone = REFORGE_STONES.get(a.reforge().toLowerCase(Locale.ROOT));
            if (stone != null) out.add(new UpgradeComponent(stone, 1, "Reforge stone"));
        }
        out.addAll(slotUnlocks(a, catalog));
        return out;
    }

    /** Coins and gems consumed to unlock the item's unlocked gemstone slots (from the items API). */
    static List<UpgradeComponent> slotUnlocks(ItemAttributes a, ItemCatalog catalog) {
        if (a.unlockedGemSlots() == null || a.unlockedGemSlots().isEmpty() || catalog == null) return List.of();
        SkyblockItem info = catalog.get(a.skyblockId());
        if (info == null || info.gemSlotCosts() == null || info.gemSlotCosts().isEmpty()) return List.of();
        Map<String, Integer> total = new TreeMap<>();
        for (String slot : a.unlockedGemSlots()) {
            // "COMBAT_1" -> the second slot of type COMBAT in API order.
            int us = slot.lastIndexOf('_');
            if (us <= 0) continue;
            String type = slot.substring(0, us);
            int idx;
            try {
                idx = Integer.parseInt(slot.substring(us + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            int seen = 0;
            for (SkyblockItem.GemSlot gs : info.gemSlotCosts()) {
                if (!gs.slotType().equalsIgnoreCase(type)) continue;
                if (seen++ == idx) {
                    gs.costs().forEach((id, n) -> total.merge(id, n, Integer::sum));
                    break;
                }
            }
        }
        List<UpgradeComponent> out = new ArrayList<>();
        total.forEach((id, n) -> out.add(new UpgradeComponent(UNLOCK_PREFIX + id, n, "Gem slot unlock")));
        return out;
    }

    private static boolean isDrillPart(String id) {
        return id.endsWith("_DRILL_ENGINE") || id.endsWith("_FUEL_TANK") || id.startsWith("GOBLIN_OMELETTE")
                || id.equals("SUNNY_SIDE_GOBLIN_OMELETTE") || id.equals("STARFALL_SEASONING");
    }

    /** Which recovery class a component id belongs to (works on ids decoded from stored sales, too). */
    public static Kind kind(String id) {
        if (id.startsWith(UNLOCK_PREFIX)) return Kind.SLOT_UNLOCK;
        if (id.equals("RECOMBOBULATOR_3000")) return Kind.RECOMBOBULATOR;
        if (id.equals("HOT_POTATO_BOOK")) return Kind.HOT_POTATO;
        if (id.equals("FUMING_POTATO_BOOK")) return Kind.FUMING_POTATO;
        if (id.equals("THE_ART_OF_WAR")) return Kind.ART_OF_WAR;
        if (id.endsWith("_MASTER_STAR")) return Kind.MASTER_STAR;
        if (id.endsWith("_GEM")) return Kind.GEMSTONE;
        if (id.startsWith("ENCHANTMENT_ULTIMATE_")) return Kind.ULTIMATE_ENCHANT;
        if (id.startsWith("ENCHANTMENT_")) return Kind.ENCHANT;
        if (id.endsWith("_SCROLL")) return Kind.SCROLL;
        if (id.startsWith("PET_ITEM_") || id.endsWith("_PET_ITEM")) return Kind.PET_ITEM;
        if (REFORGE_STONES.containsValue(id)) return Kind.REFORGE_STONE;
        if (isDrillPart(id)) return Kind.DRILL_PART;
        return Kind.OTHER;
    }

    /** Recovery rate for one component kind, from the configured base rate. */
    public static double recovery(Kind k, double baseRate) {
        return Math.min(MAX_RECOVERY, Math.max(0, baseRate * k.weight));
    }

    public record Result(double value, double fullCost, int priced, int unpriced, List<String> lines) {}

    /** Values components at their kind's share of the base recovery rate, ignoring enchants under 5k. */
    public static Result value(List<UpgradeComponent> comps, PriceSource prices, double baseRecovery) {
        return value(comps, prices, baseRecovery, DEFAULT_MIN_ENCHANT_COST);
    }

    /**
     * Values components at their kind's share of the configured base recovery rate.
     *
     * @param minEnchantCost enchant books with a full cost below this are ignored
     */
    public static Result value(List<UpgradeComponent> comps, PriceSource prices, double baseRecovery, double minEnchantCost) {
        double value = 0, full = 0;
        int priced = 0, unpriced = 0;
        List<String> lines = new ArrayList<>();
        for (UpgradeComponent c : comps) {
            if (c.itemId().startsWith("~") || c.quantity() <= 0) continue; // markers, corrupt counts
            Kind kind = kind(c.itemId());
            Priced p = unitPrice(c.itemId(), prices);
            if (!Stats.plausibleCoins(p.price)) {
                // Unknown cheap enchants are common and harmless; only report the others.
                if (kind != Kind.ENCHANT) {
                    unpriced++;
                    lines.add(c.label() + " " + c.itemId() + " x" + c.quantity() + ": no price (valued at 0)");
                }
                continue;
            }
            double cost = p.price * c.quantity();
            if (!Stats.plausibleCoins(cost)) {
                unpriced++;
                lines.add(c.label() + " " + c.itemId() + ": price out of range (valued at 0)");
                continue;
            }
            if (kind == Kind.ENCHANT && cost < minEnchantCost) continue;
            double rate = recovery(kind, baseRecovery);
            priced++;
            full += cost;
            value += cost * rate;
            lines.add(String.format(Locale.ROOT, "%s x%d: cost %.0f%s -> credited %.0f (%.0f%%)", c.itemId(), c.quantity(), cost,
                    p.derived ? " (derived from lower level)" : "", cost * rate, rate * 100));
        }
        return new Result(value, full, priced, unpriced, lines);
    }

    private record Priced(double price, boolean derived) {}

    private static Priced unitPrice(String id, PriceSource prices) {
        if (id.startsWith(UNLOCK_PREFIX)) {
            String inner = id.substring(UNLOCK_PREFIX.length());
            return new Priced("COINS".equals(inner) ? 1 : prices.price(inner), false);
        }
        double direct = prices.price(id);
        if (direct > 0 && !Double.isInfinite(direct)) return new Priced(direct, false);
        if (!id.startsWith("ENCHANTMENT_")) return new Priced(Double.NaN, false);
        // ENCHANTMENT_<NAME>_<LEVEL>: two level-N books combine into one level N+1 book.
        int us = id.lastIndexOf('_');
        int level;
        try {
            level = Integer.parseInt(id.substring(us + 1));
        } catch (NumberFormatException e) {
            return new Priced(Double.NaN, false);
        }
        String stem = id.substring(0, us + 1);
        for (int k = level - 1; k >= 1 && level - k <= 4; k--) {
            double p = prices.price(stem + k);
            if (p > 0 && !Double.isInfinite(p)) return new Priced(p * Math.pow(2, level - k) * DERIVED_HAIRCUT, true);
        }
        return new Priced(Double.NaN, false);
    }
}
