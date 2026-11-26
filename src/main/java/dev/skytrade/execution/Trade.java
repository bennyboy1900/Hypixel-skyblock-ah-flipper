package dev.skytrade.execution;

import dev.skytrade.strategy.Opportunity;

/**
 * Mutable trade record, owned by the single trader thread and persisted after every transition.
 * Quantities are cumulative and only ever increase, so replaying a fill report can never double count.
 */
public final class Trade {
    public String id;
    public Opportunity.Kind kind;
    public String strategy;
    public String itemId;
    public String name;
    /** Auction UUID for AH trades. */
    public String auctionUuid;
    public boolean manual;

    public TradeState state = TradeState.IDLE;
    public TradeState resumeState;
    public long createdAt;
    public long stateSince;
    public long updatedAt;

    public double buyPrice;
    public double sellPrice;
    public long targetQty;
    public long filledBuyQty;
    public long collectedQty;
    public long filledSellQty;
    public long sellListedQty;

    public String buyOrderId;
    public String sellOrderId;
    public int buyAttempt;
    public int sellAttempt;
    public int repriceCount;

    public double costBasis;
    public double proceeds;
    public double fees;
    /** Ledger sequence counter: each money movement gets a unique (trade id, seq). */
    public int ledgerSeq;

    /** Cumulative fills recorded before the current buy/sell order (orders are replaced on reprice). */
    public long buyOrderBase;
    public long sellOrderBase;
    /** "BAZAAR" or "AUCTION" exit venue. */
    public String venue = "BAZAAR";
    public double salesPerDay;
    /** Lowest acceptable exit price before the holding limit forces an exit. */
    public double minSellPrice;
    public long completedAt;

    public double predictedProfit;
    public double predictedHoldHours;
    public double markPrice;

    public int errorCount;
    public String lastError = "";
    public String note = "";

    public long heldQty() {
        return Math.max(0, collectedQty - filledSellQty);
    }

    public long remainingBuyQty() {
        return Math.max(0, targetQty - filledBuyQty);
    }

    public double realizedProfit() {
        if (filledSellQty == 0 || filledBuyQty == 0) return 0;
        double avgCost = costBasis / filledBuyQty;
        return proceeds - avgCost * filledSellQty;
    }

    public double unrealizedProfit() {
        if (filledBuyQty == 0) return 0;
        double avgCost = costBasis / filledBuyQty;
        long open = Math.max(0, filledBuyQty - filledSellQty);
        return open * (markPrice - avgCost);
    }

    public double progress() {
        if (state.ordinal() <= TradeState.PARTIALLY_FILLED.ordinal() || state == TradeState.BUY_COMPLETED) {
            return targetQty == 0 ? 0 : (double) filledBuyQty / targetQty * 0.5;
        }
        long sellBase = Math.max(1, filledBuyQty);
        return 0.5 + 0.5 * filledSellQty / sellBase;
    }

    public String buyClientId() {
        return id + ":buy:" + buyAttempt;
    }

    public String sellClientId() {
        return id + ":sell:" + sellAttempt;
    }

    /** Detached copy for publishing to the UI thread. */
    public Trade copy() {
        Trade c = new Trade();
        c.id = id;
        c.kind = kind;
        c.strategy = strategy;
        c.itemId = itemId;
        c.name = name;
        c.auctionUuid = auctionUuid;
        c.manual = manual;
        c.state = state;
        c.resumeState = resumeState;
        c.createdAt = createdAt;
        c.stateSince = stateSince;
        c.updatedAt = updatedAt;
        c.buyPrice = buyPrice;
        c.sellPrice = sellPrice;
        c.targetQty = targetQty;
        c.filledBuyQty = filledBuyQty;
        c.collectedQty = collectedQty;
        c.filledSellQty = filledSellQty;
        c.sellListedQty = sellListedQty;
        c.buyOrderId = buyOrderId;
        c.sellOrderId = sellOrderId;
        c.buyAttempt = buyAttempt;
        c.sellAttempt = sellAttempt;
        c.repriceCount = repriceCount;
        c.costBasis = costBasis;
        c.proceeds = proceeds;
        c.fees = fees;
        c.ledgerSeq = ledgerSeq;
        c.buyOrderBase = buyOrderBase;
        c.sellOrderBase = sellOrderBase;
        c.venue = venue;
        c.salesPerDay = salesPerDay;
        c.minSellPrice = minSellPrice;
        c.completedAt = completedAt;
        c.predictedProfit = predictedProfit;
        c.predictedHoldHours = predictedHoldHours;
        c.markPrice = markPrice;
        c.errorCount = errorCount;
        c.lastError = lastError;
        c.note = note;
        return c;
    }
}
