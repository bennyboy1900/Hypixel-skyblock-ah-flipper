package dev.skytrade.execution;

import java.util.List;

/** Persistence port for trades and the money ledger. Commits are atomic. */
public interface TradeStore {
    /** Atomically persists the trade's state together with any new ledger entries. */
    void commit(Trade trade, List<LedgerEntry> entries);

    List<Trade> loadAll();

    List<LedgerEntry> loadLedger();

    void clear();

    /** Simple in-memory store for backtests and tests. */
    final class InMemory implements TradeStore {
        private final java.util.Map<String, Trade> trades = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, LedgerEntry> ledger = new java.util.LinkedHashMap<>();

        @Override
        public synchronized void commit(Trade trade, List<LedgerEntry> entries) {
            trades.put(trade.id, trade);
            for (LedgerEntry e : entries) ledger.putIfAbsent(e.tradeId() + "#" + e.seq(), e);
        }

        @Override
        public synchronized List<Trade> loadAll() {
            return List.copyOf(trades.values());
        }

        @Override
        public synchronized List<LedgerEntry> loadLedger() {
            return List.copyOf(ledger.values());
        }

        @Override
        public synchronized void clear() {
            trades.clear();
            ledger.clear();
        }
    }
}
