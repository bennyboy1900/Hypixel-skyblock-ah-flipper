package dev.skytrade.valuation;

import dev.skytrade.auction.ItemAttributes;

import java.util.*;

/**
 * Extracts priceable upgrade components from an item and values them at a partial recovery rate.
 * Upgrades are never valued at full purchase price: buyers rarely pay full cost for applied upgrades.
 */
public final class UpgradeValuer {
    private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};
    private static final Set<String> VALUABLE_ENCHANTS = Set.of("legion", "overload", "chimera", "soul_eater", "fatal_tempo",
            "wisdom", "last_stand", "bank", "combo", "inferno", "duplex", "one_for_all", "swarm", "rend", "habanero_tactics",
            "the_one", "refrigerate", "flash");

    private UpgradeValuer() {}

    public static List<UpgradeComponent> components(ItemAttributes a) {
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
            if (k.startsWith("ultimate_")) continue;
            if (VALUABLE_ENCHANTS.contains(k) || e.getValue() >= 6) {
                out.add(new UpgradeComponent("ENCHANTMENT_" + k.toUpperCase(Locale.ROOT) + "_" + e.getValue(), 1, "Enchant book"));
            }
        }
        if (a.isPet() && a.petHeldItem() != null) out.add(new UpgradeComponent(a.petHeldItem(), 1, "Pet item"));
        return out;
    }

    public record Result(double value, double fullCost, int priced, int unpriced, List<String> lines) {}

    /** Values components at {@code recovery} of their conservative market price. */
    public static Result value(List<UpgradeComponent> comps, PriceSource prices, double recovery) {
        double value = 0, full = 0;
        int priced = 0, unpriced = 0;
        List<String> lines = new ArrayList<>();
        for (UpgradeComponent c : comps) {
            double p = prices.price(c.itemId());
            if (!(p > 0) || Double.isInfinite(p)) {
                unpriced++;
                lines.add(c.label() + " " + c.itemId() + " x" + c.quantity() + ": no price (valued at 0)");
                continue;
            }
            priced++;
            double cost = p * c.quantity();
            full += cost;
            value += cost * recovery;
            lines.add(String.format(Locale.ROOT, "%s x%d: cost %.0f -> credited %.0f (%.0f%%)", c.itemId(), c.quantity(), cost, cost * recovery, recovery * 100));
        }
        return new Result(value, full, priced, unpriced, lines);
    }
}
