package dev.skytrade.portfolio;

import dev.skytrade.execution.LedgerEntry;
import dev.skytrade.execution.Trade;
import dev.skytrade.execution.TradeState;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.risk.SizingContext;
import dev.skytrade.util.RingBuffer;

import java.util.*;
import java.util.function.Function;

/**
 * Ledger-based portfolio. Free cash is always {@code startingCapital + sum(ledger amounts)}, so it can
 * be rebuilt exactly after a restart and duplicate ledger entries (same trade id + seq) are ignored.
 */
public final class PortfolioService {
    private double startingCapital;
    private double cash;
    private final Set<String> seenEntries = new HashSet<>();
    private final RingBuffer<CompletedTrade> completed = new RingBuffer<>(5_000);
    private volatile PortfolioView view = PortfolioView.EMPTY;

    public PortfolioService(double startingCapital) {
        this.startingCapital = startingCapital;
        this.cash = startingCapital;
    }

    public synchronized void reset(double startingCapital) {
        this.startingCapital = startingCapital;
        this.cash = startingCapital;
        seenEntries.clear();
        completed.clear();
        view = PortfolioView.EMPTY;
    }

    public synchronized boolean apply(LedgerEntry e) {
        if (!seenEntries.add(e.tradeId() + "#" + e.seq())) return false;
        cash += e.amount();
        return true;
    }

    public synchronized void recordCompletion(CompletedTrade c) {
        completed.add(c);
    }

    public synchronized double cash() {
        return cash;
    }

    public synchronized double startingCapital() {
        return startingCapital;
    }

    public List<CompletedTrade> completedTrades() {
        return completed.toList();
    }

    /**
     * Recomputes the published view.
     *
     * @param mark conservative liquidation value per unit for a trade's item
     */
    public synchronized PortfolioView publish(Collection<Trade> trades, Function<Trade, Double> mark, ItemCatalog catalog, long now) {
        double escrow = 0, inventory = 0, unrealized = 0, pphSum = 0;
        Map<String, Double> byItem = new HashMap<>();
        Map<String, Double> byGroup = new HashMap<>();
        List<Trade> active = new ArrayList<>();
        for (Trade t : trades) {
            if (t.state.terminal()) continue;
            // Escrow is booked when entering ORDER_PENDING and released when the buy side closes
            // (targetQty is then reduced to the filled quantity), so remainingBuyQty is exactly the escrow.
            double esc = 0;
            boolean preEscrow = t.state == TradeState.IDLE || t.state == TradeState.ANALYZING || t.state == TradeState.VALIDATING;
            if (!preEscrow && t.kind == dev.skytrade.strategy.Opportunity.Kind.BAZAAR_FLIP) esc = t.remainingBuyQty() * t.buyPrice;
            Double m = mark.apply(t);
            double unit = m != null && m > 0 ? m : (t.filledBuyQty > 0 ? t.costBasis / t.filledBuyQty : 0);
            t.markPrice = unit;
            long open = Math.max(0, t.filledBuyQty - t.filledSellQty);
            double inv = open * unit;
            escrow += esc;
            inventory += inv;
            unrealized += t.unrealizedProfit();
            double exposure = esc + inv;
            byItem.merge(t.itemId, exposure, Double::sum);
            byGroup.merge(catalog.group(t.itemId), exposure, Double::sum);
            if (t.predictedHoldHours > 0) pphSum += t.predictedProfit / t.predictedHoldHours;
            active.add(t.copy());
        }
        double realized = 0, daily = 0, weekly = 0;
        List<CompletedTrade> all = completed.toList();
        for (CompletedTrade c : all) {
            realized += c.realizedProfit();
            if (now - c.closedAt() < 86_400_000L) daily += c.realizedProfit();
            if (now - c.closedAt() < 7 * 86_400_000L) weekly += c.realizedProfit();
        }
        List<CompletedTrade> recent = all.size() > 50 ? all.subList(all.size() - 50, all.size()) : all;
        List<CompletedTrade> recentRev = new ArrayList<>(recent);
        Collections.reverse(recentRev);
        active.sort(Comparator.comparingLong((Trade t) -> t.createdAt).reversed());
        view = new PortfolioView(now, startingCapital, cash, escrow, inventory, cash + escrow + inventory, realized,
                unrealized, daily, weekly, pphSum, List.copyOf(active), List.copyOf(recentRev), Map.copyOf(byItem), Map.copyOf(byGroup));
        return view;
    }

    public PortfolioView view() {
        return view;
    }

    public SizingContext sizingContext(int freeSlots, boolean reinvest) {
        PortfolioView v = view;
        double value = v.totalValue() > 0 ? v.totalValue() : cash();
        if (!reinvest) value = Math.min(value, startingCapital());
        return new SizingContext(value, cash(), v.exposureByItem(), v.exposureByGroup(), freeSlots, v.activeTrades().size());
    }

    /** Net realized profit of trades closed in the last 24h (used by the daily-loss circuit breaker). */
    public double realizedLast24h(long now) {
        double s = 0;
        for (CompletedTrade c : completed.toList()) if (now - c.closedAt() < 86_400_000L) s += c.realizedProfit();
        return s;
    }
}
