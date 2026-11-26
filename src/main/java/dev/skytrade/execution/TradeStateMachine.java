package dev.skytrade.execution;

import dev.skytrade.util.Log;
import dev.skytrade.util.TimeSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies validated transitions and persists them atomically with their ledger entries.
 * Persistence happens before the in-memory change becomes visible to the next step; if persistence
 * fails the trade is moved to ERROR (in memory) and recovered on the next tick or restart.
 */
public final class TradeStateMachine {
    private final TradeStore store;
    private final TimeSource time;

    public TradeStateMachine(TradeStore store, TimeSource time) {
        this.store = store;
        this.time = time;
    }

    public static final class IllegalTransition extends RuntimeException {
        public IllegalTransition(TradeState from, TradeState to) {
            super("illegal transition " + from + " -> " + to);
        }
    }

    public void transition(Trade t, TradeState next, String reason, LedgerEntry... entries) {
        if (!t.state.canTransitionTo(next)) throw new IllegalTransition(t.state, next);
        TradeState prev = t.state;
        long now = time.now();
        if (next == TradeState.PAUSED) t.resumeState = prev;
        t.state = next;
        if (prev != next) t.stateSince = now;
        t.updatedAt = now;
        if (reason != null && !reason.isEmpty()) t.note = reason;
        persist(t, entries);
        if (prev != next) Log.debug("trade.transition", "trade", t.id, "from", prev, "to", next, "reason", reason);
    }

    /** Persists field updates without a state change (e.g. fill progress). */
    public void update(Trade t, LedgerEntry... entries) {
        t.updatedAt = time.now();
        persist(t, entries);
    }

    public LedgerEntry entry(Trade t, LedgerEntry.Type type, long qty, double price, double amount, double fee) {
        return new LedgerEntry(t.id, ++t.ledgerSeq, time.now(), type, t.itemId, qty, price, amount, fee, t.strategy);
    }

    private void persist(Trade t, LedgerEntry[] entries) {
        List<LedgerEntry> list = new ArrayList<>(List.of(entries));
        store.commit(t, list);
    }

    public boolean timedOut(Trade t, double cancelAfterHours, double maxHoldHours) {
        long limit = t.state.timeoutMs(cancelAfterHours, maxHoldHours);
        return limit > 0 && time.now() - t.stateSince > limit;
    }
}
