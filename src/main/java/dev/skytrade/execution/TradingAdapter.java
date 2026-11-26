package dev.skytrade.execution;

/**
 * Execution port. The only implementation shipped is the sandbox {@code MockMarketAdapter}; there is
 * deliberately no implementation that acts on a real server.
 */
public interface TradingAdapter {
    enum OrderState { OPEN, FILLED, CANCELLED, REJECTED, EXPIRED, UNKNOWN }

    record OrderReport(String orderId, OrderState state, long quantity, long filled, double price, String message) {}

    /** Places a bazaar buy order. Idempotent on {@code clientOrderId}: retrying returns the original order. */
    OrderReport placeBuyOrder(String clientOrderId, String itemId, long qty, double price);

    OrderReport placeSellOffer(String clientOrderId, String itemId, long qty, double price);

    OrderReport status(String orderId);

    /** Cancels; the report contains the final filled quantity. */
    OrderReport cancel(String orderId);

    /** Buys a BIN auction. FILLED on success, REJECTED if it was gone or sniped. */
    OrderReport buyAuction(String clientOrderId, String auctionUuid, double price);

    /** Lists an item on the AH; quantity is 1. FILLED once sold, EXPIRED if unsold. */
    OrderReport createAuction(String clientOrderId, String itemId, double price, double durationHours, double salesPerDay);

    /** Whether this adapter only simulates. Execution refuses to run with a non-simulated adapter. */
    boolean simulated();
}
