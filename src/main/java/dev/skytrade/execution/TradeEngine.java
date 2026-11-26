package dev.skytrade.execution;

import dev.skytrade.auction.AuctionFees;
import dev.skytrade.auction.AuctionOpportunity;
import dev.skytrade.bazaar.BazaarFees;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.crafting.CraftOpportunity;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.portfolio.CompletedTrade;
import dev.skytrade.portfolio.PortfolioService;
import dev.skytrade.risk.CircuitBreaker;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.util.Log;
import dev.skytrade.util.TimeSource;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Advances every simulated trade through {@link TradeState} using a {@link TradingAdapter}.
 * All methods must be called from a single owner thread (the trader thread).
 *
 * <p>Duplicate protection: each order uses a deterministic client id ({@code tradeId:buy:attempt}) and
 * the adapter is idempotent on it, so a crash between "place" and "persist" can never create a second
 * order. Fills are derived from cumulative adapter reports, so re-reading a report adds nothing.</p>
 */
public final class TradeEngine {
    private static final int MAX_REPRICES = 4;
    private static final int MAX_ERRORS = 3;

    private final Supplier<SkyTradeConfig> config;
    private final TradingAdapter adapter;
    private final TradeStateMachine fsm;
    private final TradeStore store;
    private final PortfolioService portfolio;
    private final CircuitBreaker breaker;
    private final TimeSource time;
    private final Map<String, Trade> trades = new LinkedHashMap<>();
    private final Map<String, Long> outbidSince = new HashMap<>();
    private Consumer<String> notifier = s -> { };
    private Consumer<CompletedTrade> onComplete = c -> { };

    public TradeEngine(Supplier<SkyTradeConfig> config, TradingAdapter adapter, TradeStore store, PortfolioService portfolio,
                       CircuitBreaker breaker, TimeSource time) {
        if (!adapter.simulated()) throw new IllegalStateException("TradeEngine only runs against simulated adapters");
        this.config = config;
        this.adapter = adapter;
        this.store = store;
        this.fsm = new TradeStateMachine(store, time);
        this.portfolio = portfolio;
        this.breaker = breaker;
        this.time = time;
    }

    public void setNotifier(Consumer<String> n) {
        notifier = n;
    }

    public void setOnComplete(Consumer<CompletedTrade> c) {
        onComplete = c;
    }

    public Collection<Trade> trades() {
        return trades.values();
    }

    public int activeCount() {
        int n = 0;
        for (Trade t : trades.values()) if (!t.state.terminal()) n++;
        return n;
    }

    /** Order slots in use: one per open buy order or sell offer. */
    public int usedSlots() {
        int n = 0;
        for (Trade t : trades.values()) {
            if (t.state.terminal()) continue;
            if (t.kind == Opportunity.Kind.BAZAAR_FLIP || "BAZAAR".equals(t.venue)) n++;
        }
        return n;
    }

    public boolean hasActive(String itemOrKey) {
        for (Trade t : trades.values()) {
            if (t.state.terminal()) continue;
            if (itemOrKey.equals(t.itemId) || itemOrKey.equals(t.auctionUuid)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ recovery

    /** Loads persisted trades and ledger; every non-terminal trade enters RECOVERY to be reconciled. */
    public void restore() {
        for (LedgerEntry e : store.loadLedger()) portfolio.apply(e);
        for (Trade t : store.loadAll()) {
            trades.put(t.id, t);
            if (t.state.terminal()) {
                if (t.completedAt > 0) portfolio.recordCompletion(completion(t, t.state == TradeState.CANCELLED));
                continue;
            }
            if (t.state == TradeState.PAUSED) continue;
            if (t.state == TradeState.IDLE || t.state == TradeState.ANALYZING || t.state == TradeState.VALIDATING) {
                // Nothing committed yet: safe to discard.
                forceCancel(t, "abandoned before order placement (restart)");
                continue;
            }
            if (t.state != TradeState.RECOVERY) {
                if (t.state.canTransitionTo(TradeState.ERROR)) fsm.transition(t, TradeState.ERROR, "restart");
                fsm.transition(t, TradeState.RECOVERY, "restart recovery");
            }
        }
        Log.info("trades.restored", "total", trades.size(), "active", activeCount(), "cash", Math.round(portfolio.cash()));
    }

    private void forceCancel(Trade t, String why) {
        if (t.state.canTransitionTo(TradeState.CANCELLED)) fsm.transition(t, TradeState.CANCELLED, why);
        else {
            t.state = TradeState.CANCELLED;
            fsm.update(t);
        }
    }

    // ------------------------------------------------------------------ opening

    public Trade open(Opportunity o, long quantity, String strategy, boolean manual) {
        long now = time.now();
        Trade t = new Trade();
        t.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        t.kind = o.kind();
        t.strategy = strategy;
        t.itemId = o.itemId();
        t.name = o.name();
        t.manual = manual;
        t.createdAt = now;
        t.stateSince = now;
        t.targetQty = quantity;
        t.buyPrice = o.entryPrice();
        t.sellPrice = o.exitPrice();
        t.predictedHoldHours = o.holdHours();
        t.predictedProfit = o.quantity() > 0 ? o.netProfit() * quantity / o.quantity() : 0;
        if (o instanceof AuctionOpportunity a) {
            t.auctionUuid = a.uuid();
            t.venue = "AUCTION";
            t.salesPerDay = a.valuation().salesPerDay();
            t.minSellPrice = a.listingPrice() * 1.02;
        } else if (o instanceof CraftOpportunity c) {
            t.venue = c.saleVenue();
            t.salesPerDay = 24 / Math.max(0.1, c.holdHours());
            t.minSellPrice = c.unitCost() * 1.01;
        } else {
            t.venue = "BAZAAR";
            t.minSellPrice = t.buyPrice / (1 - config.get().fees.bazaarTax) + 0.1;
        }
        trades.put(t.id, t);
        fsm.transition(t, TradeState.ANALYZING, "selected " + strategy);
        return t;
    }

    /**
     * Validation passed: commit capital and move to ORDER_PENDING. Escrow (bazaar) is booked here, in the
     * same atomic commit as the state change.
     */
    public void commit(Trade t) {
        fsm.transition(t, TradeState.VALIDATING, "risk checks");
        double required = t.targetQty * t.buyPrice;
        if (required > portfolio.cash()) {
            fsm.transition(t, TradeState.CANCELLED, "insufficient cash");
            return;
        }
        if (t.kind == Opportunity.Kind.BAZAAR_FLIP) {
            LedgerEntry esc = fsm.entry(t, LedgerEntry.Type.ORDER_ESCROW, t.targetQty, t.buyPrice, -t.targetQty * t.buyPrice, 0);
            fsm.transition(t, TradeState.ORDER_PENDING, "escrow booked", esc);
            portfolio.apply(esc);
        } else {
            fsm.transition(t, TradeState.ORDER_PENDING, "awaiting purchase");
        }
    }

    public void reject(Trade t, String reason) {
        if (t.state.canTransitionTo(TradeState.CANCELLED)) fsm.transition(t, TradeState.CANCELLED, reason);
    }

    // ------------------------------------------------------------------ control

    public void pauseAll(String reason) {
        for (Trade t : trades.values()) {
            if (!t.state.terminal() && t.state != TradeState.PAUSED && t.state.canTransitionTo(TradeState.PAUSED)) {
                fsm.transition(t, TradeState.PAUSED, reason);
            }
        }
    }

    public void resumeAll() {
        for (Trade t : trades.values()) {
            if (t.state == TradeState.PAUSED) {
                TradeState back = t.resumeState != null && TradeState.PAUSED.canTransitionTo(t.resumeState) ? t.resumeState : TradeState.RECOVERY;
                fsm.transition(t, back, "resumed");
            }
        }
    }

    /** User-requested cancel: cancels open orders and refunds escrow. Held inventory stays and is sold. */
    public void cancel(String tradeId) {
        Trade t = trades.get(tradeId);
        if (t == null || t.state.terminal()) return;
        if (t.state == TradeState.BUY_ORDER_ACTIVE || t.state == TradeState.PARTIALLY_FILLED || t.state == TradeState.ORDER_PENDING) {
            closeBuy(t, "cancelled by user");
        }
    }

    // ------------------------------------------------------------------ main loop

    public void tick(BazaarSnapshot market) {
        SkyTradeConfig cfg = config.get();
        for (Trade t : new ArrayList<>(trades.values())) {
            if (t.state.terminal() || t.state == TradeState.PAUSED) continue;
            try {
                step(t, market, cfg);
            } catch (TradeStateMachine.IllegalTransition e) {
                Log.error("trade.illegal_transition", e, "trade", t.id);
                markError(t, e.getMessage());
            } catch (RuntimeException e) {
                Log.error("trade.step_failed", e, "trade", t.id, "state", t.state);
                markError(t, String.valueOf(e.getMessage()));
            }
        }
    }

    private void markError(Trade t, String msg) {
        t.errorCount++;
        t.lastError = msg;
        breaker.error();
        if (t.state.canTransitionTo(TradeState.ERROR)) fsm.transition(t, TradeState.ERROR, msg);
        else fsm.update(t);
    }

    private void step(Trade t, BazaarSnapshot market, SkyTradeConfig cfg) {
        double cancelAfter = cfg.sandbox.cancelAfterHours;
        double maxHold = cfg.risk.maxHoldingHours;
        switch (t.state) {
            case ANALYZING, VALIDATING -> {
                if (fsm.timedOut(t, cancelAfter, maxHold)) fsm.transition(t, TradeState.CANCELLED, "validation timeout");
            }
            case ORDER_PENDING -> placeEntry(t, market, cfg);
            case BUY_ORDER_ACTIVE, PARTIALLY_FILLED -> manageBuy(t, market, cfg);
            case BUY_COMPLETED -> placeExit(t, market, cfg);
            case SELL_ORDER_ACTIVE -> manageSell(t, market, cfg);
            case ERROR -> {
                if (t.errorCount > MAX_ERRORS * 3) {
                    // Persistent failure: recover anyway but stop retrying placements.
                    t.note = "repeated errors";
                }
                if (fsm.timedOut(t, cancelAfter, maxHold)) fsm.transition(t, TradeState.RECOVERY, "retry after error");
            }
            case RECOVERY -> recover(t);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ entry

    private void placeEntry(Trade t, BazaarSnapshot market, SkyTradeConfig cfg) {
        switch (t.kind) {
            case BAZAAR_FLIP -> {
                TradingAdapter.OrderReport r = adapter.placeBuyOrder(t.buyClientId(), t.itemId, t.remainingBuyQty(), t.buyPrice);
                if (r.state() == TradingAdapter.OrderState.REJECTED || r.orderId() == null) {
                    breaker.error();
                    t.buyAttempt++;
                    t.errorCount++;
                    if (t.errorCount >= MAX_ERRORS || t.filledBuyQty > 0) {
                        closeBuy(t, "order rejected: " + r.message());
                    } else {
                        t.lastError = r.message();
                        fsm.update(t); // stay ORDER_PENDING, retry next tick with a new client id
                    }
                    return;
                }
                breaker.success();
                t.buyOrderId = r.orderId();
                t.buyOrderBase = t.filledBuyQty;
                fsm.transition(t, TradeState.BUY_ORDER_ACTIVE, "buy order placed");
            }
            case AUCTION_FLIP -> {
                TradingAdapter.OrderReport r = adapter.buyAuction(t.buyClientId(), t.auctionUuid, t.buyPrice);
                if (r.state() != TradingAdapter.OrderState.FILLED) {
                    fsm.transition(t, TradeState.CANCELLED, "purchase failed: " + r.message());
                    return;
                }
                t.buyOrderId = r.orderId();
                LedgerEntry buy = fsm.entry(t, LedgerEntry.Type.AH_BUY, 1, t.buyPrice, -t.buyPrice, 0);
                t.filledBuyQty = 1;
                t.collectedQty = 1;
                t.costBasis = t.buyPrice;
                fsm.transition(t, TradeState.BUY_COMPLETED, "auction purchased", buy);
                portfolio.apply(buy);
                notifier.accept("Bought " + t.name + " for " + dev.skytrade.util.Fmt.coins(t.buyPrice) + " (sandbox)");
            }
            case CRAFT_FLIP -> {
                // Materials are bought at the instant-buy / lowest-BIN prices used in the cost estimate.
                double total = t.buyPrice * t.targetQty;
                t.buyOrderId = "materials:" + t.id;
                LedgerEntry buy = fsm.entry(t, LedgerEntry.Type.AH_BUY, t.targetQty, t.buyPrice, -total, 0);
                t.filledBuyQty = t.targetQty;
                t.collectedQty = t.targetQty;
                t.costBasis = total;
                fsm.transition(t, TradeState.BUY_COMPLETED, "materials bought and crafted", buy);
                portfolio.apply(buy);
            }
        }
    }

    private void manageBuy(Trade t, BazaarSnapshot market, SkyTradeConfig cfg) {
        TradingAdapter.OrderReport r = adapter.status(t.buyOrderId);
        if (r.state() == TradingAdapter.OrderState.UNKNOWN) {
            markError(t, "buy order unknown to adapter");
            return;
        }
        applyBuyFills(t, r);
        if (t.filledBuyQty >= t.targetQty) {
            fsm.transition(t, TradeState.BUY_COMPLETED, "buy order filled");
            notifyFill(cfg, "Buy order filled: " + t.targetQty + "x " + t.name);
            return;
        }
        if (r.state() == TradingAdapter.OrderState.CANCELLED || r.state() == TradingAdapter.OrderState.EXPIRED) {
            closeBuy(t, "order closed by market");
            return;
        }
        if (fsm.timedOut(t, cfg.sandbox.cancelAfterHours, cfg.risk.maxHoldingHours)) {
            closeBuy(t, "buy order timed out");
            return;
        }
        // Repricing when outbid for long enough and the flip still works at the new price.
        BazaarProduct p = market == null ? null : market.get(t.itemId);
        if (p == null) return;
        String key = t.id + ":buy";
        if (p.bestBid() > t.buyPrice + 1e-9) {
            long since = outbidSince.computeIfAbsent(key, k -> time.now());
            if (time.now() - since > cfg.sandbox.repriceAfterMinutes * 60_000 && t.repriceCount < MAX_REPRICES) {
                double newPrice = Math.round((p.bestBid() + BazaarFees.tick()) * 10) / 10.0;
                double exit = p.bestAsk() - BazaarFees.tick();
                double margin = exit * (1 - cfg.fees.bazaarTax) - newPrice;
                if (margin > 0 && newPrice <= t.buyPrice * 1.05) repriceBuy(t, newPrice);
                outbidSince.remove(key);
            }
        } else {
            outbidSince.remove(key);
        }
    }

    private void applyBuyFills(Trade t, TradingAdapter.OrderReport r) {
        long cumulative = t.buyOrderBase + r.filled();
        long delta = cumulative - t.filledBuyQty;
        if (delta <= 0) return;
        t.filledBuyQty += delta;
        t.collectedQty += delta; // simulated items are collected immediately
        t.costBasis += delta * r.price();
        if (t.state == TradeState.BUY_ORDER_ACTIVE || t.state == TradeState.PARTIALLY_FILLED) {
            if (t.filledBuyQty < t.targetQty) fsm.transition(t, TradeState.PARTIALLY_FILLED, delta + " filled");
            else fsm.update(t);
        } else {
            fsm.update(t);
        }
    }

    private void repriceBuy(Trade t, double newPrice) {
        TradingAdapter.OrderReport c = adapter.cancel(t.buyOrderId);
        applyBuyFills(t, c);
        long thisOrderUnfilled = Math.max(0, c.quantity() - c.filled());
        LedgerEntry refund = fsm.entry(t, LedgerEntry.Type.ORDER_REFUND, thisOrderUnfilled, c.price(), thisOrderUnfilled * c.price(), 0);
        long remaining = t.remainingBuyQty();
        if (remaining <= 0) {
            fsm.transition(t, TradeState.BUY_COMPLETED, "filled during reprice", refund);
            portfolio.apply(refund);
            return;
        }
        t.repriceCount++;
        t.buyAttempt++;
        t.buyPrice = newPrice;
        LedgerEntry escrow = fsm.entry(t, LedgerEntry.Type.ORDER_ESCROW, remaining, newPrice, -remaining * newPrice, 0);
        TradingAdapter.OrderReport r = adapter.placeBuyOrder(t.buyClientId(), t.itemId, remaining, newPrice);
        portfolio.apply(refund);
        if (r.state() == TradingAdapter.OrderState.REJECTED || r.orderId() == null) {
            t.buyOrderId = null;
            t.targetQty = t.filledBuyQty;
            if (t.filledBuyQty > 0) fsm.transition(t, TradeState.BUY_COMPLETED, "reprice rejected; keeping partial fill", refund);
            else fsm.transition(t, TradeState.CANCELLED, "reprice rejected", refund);
            return;
        }
        t.buyOrderId = r.orderId();
        t.buyOrderBase = t.filledBuyQty;
        fsm.transition(t, t.state, "repriced buy to " + newPrice, refund, escrow);
        portfolio.apply(escrow);
    }

    /** Cancels the live buy order (if any), refunds unfilled escrow, and moves on with what was filled. */
    private void closeBuy(Trade t, String reason) {
        LedgerEntry refund = null;
        if (t.kind == Opportunity.Kind.BAZAAR_FLIP) {
            long unfilledOnOrder;
            double price = t.buyPrice;
            if (t.buyOrderId != null) {
                TradingAdapter.OrderReport c = adapter.cancel(t.buyOrderId);
                applyBuyFills(t, c);
                unfilledOnOrder = Math.max(0, c.quantity() - c.filled());
                price = c.price();
            } else {
                unfilledOnOrder = t.remainingBuyQty(); // escrow booked but no live order
            }
            if (unfilledOnOrder > 0) refund = fsm.entry(t, LedgerEntry.Type.ORDER_REFUND, unfilledOnOrder, price, unfilledOnOrder * price, 0);
        }
        LedgerEntry[] e = refund == null ? new LedgerEntry[0] : new LedgerEntry[]{refund};
        if (t.filledBuyQty > 0) {
            t.targetQty = t.filledBuyQty;
            fsm.transition(t, TradeState.BUY_COMPLETED, reason + " (partial fill kept)", e);
        } else {
            fsm.transition(t, TradeState.CANCELLED, reason, e);
            t.completedAt = time.now();
            fsm.update(t);
            CompletedTrade ct = completion(t, true);
            portfolio.recordCompletion(ct);
        }
        if (refund != null) portfolio.apply(refund);
    }

    // ------------------------------------------------------------------ exit

    private void placeExit(Trade t, BazaarSnapshot market, SkyTradeConfig cfg) {
        long qty = t.heldQty();
        if (qty <= 0) {
            complete(t);
            return;
        }
        if ("AUCTION".equals(t.venue)) {
            double price = Math.max(t.sellPrice, t.minSellPrice);
            double fee = AuctionFees.listingFee(price, cfg.fees);
            TradingAdapter.OrderReport r = adapter.createAuction(t.sellClientId(), t.itemId, price, 48, Math.max(0.1, t.salesPerDay));
            if (r.state() == TradingAdapter.OrderState.REJECTED || r.orderId() == null) {
                t.sellAttempt++;
                markError(t, "listing rejected: " + r.message());
                return;
            }
            t.sellOrderId = r.orderId();
            t.sellOrderBase = t.filledSellQty;
            t.sellPrice = price;
            t.sellListedQty = qty;
            LedgerEntry le = fsm.entry(t, LedgerEntry.Type.AH_LISTING_FEE, 1, price, -fee, fee);
            t.fees += fee;
            fsm.transition(t, TradeState.SELL_ORDER_ACTIVE, "listed on AH", le);
            portfolio.apply(le);
            return;
        }
        BazaarProduct p = market == null ? null : market.get(t.itemId);
        double price = t.sellPrice;
        if (p != null && p.bestAsk() > 0) price = Math.round((p.bestAsk() - BazaarFees.tick()) * 10) / 10.0;
        price = Math.max(price, t.minSellPrice);
        TradingAdapter.OrderReport r = adapter.placeSellOffer(t.sellClientId(), t.itemId, qty, price);
        if (r.state() == TradingAdapter.OrderState.REJECTED || r.orderId() == null) {
            t.sellAttempt++;
            markError(t, "sell offer rejected: " + r.message());
            return;
        }
        breaker.success();
        t.sellOrderId = r.orderId();
        t.sellOrderBase = t.filledSellQty;
        t.sellPrice = price;
        t.sellListedQty = qty;
        fsm.transition(t, TradeState.SELL_ORDER_ACTIVE, "sell offer placed");
    }

    private void manageSell(Trade t, BazaarSnapshot market, SkyTradeConfig cfg) {
        TradingAdapter.OrderReport r = adapter.status(t.sellOrderId);
        if (r.state() == TradingAdapter.OrderState.UNKNOWN) {
            markError(t, "sell order unknown to adapter");
            return;
        }
        applySellFills(t, r, cfg);
        if (t.filledSellQty >= t.filledBuyQty) {
            complete(t);
            return;
        }
        boolean overdue = time.now() - t.createdAt > cfg.risk.maxHoldingHours * 3_600_000;
        if ("AUCTION".equals(t.venue)) {
            if (r.state() == TradingAdapter.OrderState.EXPIRED || r.state() == TradingAdapter.OrderState.CANCELLED) {
                // Unsold: relist cheaper, but not below cost unless we are past the holding limit.
                t.repriceCount++;
                t.sellAttempt++;
                double cut = t.repriceCount >= 3 ? 0.85 : 0.95;
                double floor = overdue ? t.costBasis * 0.7 : t.minSellPrice;
                t.sellPrice = Math.max(floor, t.sellPrice * cut);
                t.sellOrderId = null;
                fsm.transition(t, TradeState.BUY_COMPLETED, "listing expired; relisting at " + Math.round(t.sellPrice));
            }
            return;
        }
        BazaarProduct p = market == null ? null : market.get(t.itemId);
        if (p == null) return;
        String key = t.id + ":sell";
        boolean undercut = p.bestAsk() < t.sellPrice - 1e-9;
        if (undercut || overdue) {
            long since = outbidSince.computeIfAbsent(key, k -> time.now());
            if (time.now() - since > cfg.sandbox.repriceAfterMinutes * 60_000 || overdue) {
                double target = Math.round((p.bestAsk() - BazaarFees.tick()) * 10) / 10.0;
                double floor = overdue ? p.bestBid() : t.minSellPrice;
                double newPrice = Math.max(target, floor);
                if (Math.abs(newPrice - t.sellPrice) > 0.05 && (t.repriceCount < MAX_REPRICES || overdue)) repriceSell(t, newPrice, cfg);
                outbidSince.remove(key);
            }
        } else {
            outbidSince.remove(key);
        }
    }

    private void applySellFills(Trade t, TradingAdapter.OrderReport r, SkyTradeConfig cfg) {
        long cumulative = t.sellOrderBase + r.filled();
        long delta = cumulative - t.filledSellQty;
        if (delta <= 0) return;
        double gross = delta * r.price();
        double fee, net;
        LedgerEntry e;
        if ("AUCTION".equals(t.venue)) {
            fee = AuctionFees.claimTax(r.price(), cfg.fees);
            net = gross - fee;
            e = fsm.entry(t, LedgerEntry.Type.AH_SALE, delta, r.price(), net, fee);
        } else {
            fee = BazaarFees.tax(r.price(), delta, cfg.fees);
            net = gross - fee;
            e = fsm.entry(t, LedgerEntry.Type.SELL_FILL, delta, r.price(), net, fee);
        }
        t.filledSellQty += delta;
        t.proceeds += net;
        t.fees += fee;
        fsm.update(t, e);
        portfolio.apply(e);
    }

    private void repriceSell(Trade t, double newPrice, SkyTradeConfig cfg) {
        TradingAdapter.OrderReport c = adapter.cancel(t.sellOrderId);
        applySellFills(t, c, cfg);
        if (t.filledSellQty >= t.filledBuyQty) {
            complete(t);
            return;
        }
        t.repriceCount++;
        t.sellAttempt++;
        long qty = t.heldQty();
        TradingAdapter.OrderReport r = adapter.placeSellOffer(t.sellClientId(), t.itemId, qty, newPrice);
        if (r.state() == TradingAdapter.OrderState.REJECTED || r.orderId() == null) {
            t.sellOrderId = null;
            markError(t, "sell reprice rejected");
            return;
        }
        t.sellOrderId = r.orderId();
        t.sellOrderBase = t.filledSellQty;
        t.sellPrice = newPrice;
        t.sellListedQty = qty;
        fsm.transition(t, TradeState.SELL_ORDER_ACTIVE, "repriced sell to " + newPrice);
    }

    private void complete(Trade t) {
        t.completedAt = time.now();
        fsm.transition(t, TradeState.SALE_COMPLETED, "sold");
        CompletedTrade c = completion(t, false);
        portfolio.recordCompletion(c);
        onComplete.accept(c);
        notifyFill(config.get(), "Trade closed: " + t.name + " " + dev.skytrade.util.Fmt.signedCoins(c.realizedProfit()) + " (simulated)");
    }

    private CompletedTrade completion(Trade t, boolean cancelled) {
        double hold = (Math.max(t.completedAt, t.updatedAt) - t.createdAt) / 3_600_000.0;
        return new CompletedTrade(t.id, t.itemId, t.name, t.strategy, t.createdAt, t.completedAt > 0 ? t.completedAt : t.updatedAt,
                t.costBasis, cancelled ? 0 : t.realizedProfit(), t.predictedProfit, hold, t.predictedHoldHours, cancelled);
    }

    private void notifyFill(SkyTradeConfig cfg, String msg) {
        if (cfg.notifications.orderFillAlerts) notifier.accept(msg);
    }

    // ------------------------------------------------------------------ reconciliation

    /** Rebuilds the correct state from adapter reports after an error or restart. */
    private void recover(Trade t) {
        if (t.sellOrderId != null) {
            TradingAdapter.OrderReport r = adapter.status(t.sellOrderId);
            if (r.state() != TradingAdapter.OrderState.UNKNOWN) {
                fsm.transition(t, TradeState.SELL_ORDER_ACTIVE, "recovered sell order");
                return;
            }
            t.sellOrderId = null;
        }
        if (t.heldQty() > 0 && t.remainingBuyQty() == 0) {
            fsm.transition(t, TradeState.BUY_COMPLETED, "recovered inventory");
            return;
        }
        if (t.buyOrderId != null && t.kind == Opportunity.Kind.BAZAAR_FLIP) {
            TradingAdapter.OrderReport r = adapter.status(t.buyOrderId);
            if (r.state() == TradingAdapter.OrderState.UNKNOWN) {
                // The order was never acknowledged; re-place with the same client id (idempotent).
                t.buyOrderId = null;
                fsm.transition(t, TradeState.ORDER_PENDING, "re-placing unacknowledged order");
                return;
            }
            applyBuyFills(t, r);
            if (t.filledBuyQty >= t.targetQty) fsm.transition(t, TradeState.BUY_COMPLETED, "recovered: filled");
            else if (r.state() == TradingAdapter.OrderState.OPEN)
                fsm.transition(t, t.filledBuyQty > 0 ? TradeState.PARTIALLY_FILLED : TradeState.BUY_ORDER_ACTIVE, "recovered open order");
            else closeBuy(t, "recovered closed order");
            return;
        }
        if (t.heldQty() > 0) {
            fsm.transition(t, TradeState.BUY_COMPLETED, "recovered inventory");
            return;
        }
        if (t.errorCount > MAX_ERRORS * 3) {
            closeOrCancel(t);
            return;
        }
        fsm.transition(t, TradeState.ORDER_PENDING, "recovered before placement");
    }

    private void closeOrCancel(Trade t) {
        if (t.kind == Opportunity.Kind.BAZAAR_FLIP) {
            closeBuy(t, "abandoned after repeated errors");
        } else {
            fsm.transition(t, TradeState.CANCELLED, "abandoned after repeated errors");
        }
    }

    /** Removes terminal trades older than {@code maxAgeMs} from memory (they remain in the database). */
    public void prune(long maxAgeMs) {
        long now = time.now();
        trades.values().removeIf(t -> t.state.terminal() && now - t.updatedAt > maxAgeMs);
    }

    public void clear() {
        trades.clear();
        outbidSince.clear();
    }
}
