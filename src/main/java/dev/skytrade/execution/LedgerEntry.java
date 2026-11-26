package dev.skytrade.execution;

/**
 * Immutable money movement. {@code amount} is the change in free cash (negative = spent).
 * The pair (tradeId, seq) is unique; persisting the same entry twice is a no-op.
 */
public record LedgerEntry(String tradeId, int seq, long timestamp, Type type, String itemId, long quantity,
                          double price, double amount, double fee, String strategy) {
    public enum Type { ORDER_ESCROW, ORDER_REFUND, SELL_FILL, AH_BUY, AH_LISTING_FEE, AH_SALE, ADJUSTMENT }
}
