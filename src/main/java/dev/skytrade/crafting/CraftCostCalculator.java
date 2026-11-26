package dev.skytrade.crafting;

import java.util.*;

/**
 * Recursive cheapest-acquisition cost: for each item, the minimum of buying it (bazaar instant-buy
 * or lowest clean BIN) and crafting it from a verified recipe. Memoised and cycle-safe.
 */
public final class CraftCostCalculator {
    public interface Market {
        /** Conservative purchase price per unit, NaN if not purchasable. */
        double buyPrice(String id);

        String buyMethod(String id);
    }

    private final RecipeRepository recipes;
    private final Market market;
    private final int maxDepth;
    private final Map<String, CostBreakdown> memo = new HashMap<>();

    public CraftCostCalculator(RecipeRepository recipes, Market market, int maxDepth) {
        this.recipes = recipes;
        this.market = market;
        this.maxDepth = maxDepth;
    }

    /** Unit acquisition cost of an item. */
    public CostBreakdown unitCost(String id) {
        return unit(id, 0, new HashSet<>());
    }

    /** Cost to craft one batch of the recipe (inputs acquired at their cheapest), per output unit. */
    public CostBreakdown craftCost(Recipe r) {
        return craft(r, 0, new HashSet<>(Set.of(r.outputId())));
    }

    private CostBreakdown unit(String id, int depth, Set<String> visiting) {
        CostBreakdown cached = memo.get(id);
        if (cached != null) return cached;
        double buy = market.buyPrice(id);
        CostBreakdown best = Double.isFinite(buy) && buy > 0
                ? new CostBreakdown(id, 1, buy, market.buyMethod(id), List.of())
                : new CostBreakdown(id, 1, Double.NaN, "unavailable", List.of());
        if (depth < maxDepth && visiting.add(id)) {
            for (Recipe r : recipes.forOutput(id)) {
                CostBreakdown c = craft(r, depth + 1, visiting);
                if (c.priced() && (!best.priced() || c.unitCost() < best.unitCost())) best = c;
            }
            visiting.remove(id);
        }
        if (visiting.isEmpty() || depth == 0) memo.put(id, best);
        return best;
    }

    private CostBreakdown craft(Recipe r, int depth, Set<String> visiting) {
        double total = 0;
        List<CostBreakdown> kids = new ArrayList<>();
        for (Map.Entry<String, Integer> in : r.inputs().entrySet()) {
            if (visiting.contains(in.getKey())) return new CostBreakdown(r.outputId(), 1, Double.NaN, "cycle", List.of());
            CostBreakdown u = unit(in.getKey(), depth, visiting);
            if (!u.priced()) return new CostBreakdown(r.outputId(), 1, Double.NaN, "missing price: " + in.getKey(), List.of());
            kids.add(new CostBreakdown(u.itemId(), in.getValue(), u.unitCost(), u.method(), u.children()));
            total += u.unitCost() * in.getValue();
        }
        return new CostBreakdown(r.outputId(), 1, total / r.outputCount(), "craft (" + r.source() + ")", List.copyOf(kids));
    }
}
