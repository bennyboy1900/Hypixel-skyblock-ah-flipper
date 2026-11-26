package dev.skytrade.execution.sandbox;

import dev.skytrade.execution.TradingAdapter;
import dev.skytrade.simulation.MarketSimulator;
import dev.skytrade.simulation.SimOrder;

/**
 * Sandbox execution adapter backed exclusively by the local {@link MarketSimulator}.
 * It has no access to the network, the game connection, chat, or any screen.
 */
public final class MockMarketAdapter implements TradingAdapter {
    private final MarketSimulator sim;

    public MockMarketAdapter(MarketSimulator sim) {
        this.sim = sim;
    }

    @Override
    public OrderReport placeBuyOrder(String clientOrderId, String itemId, long qty, double price) {
        return report(sim.place(clientOrderId, SimOrder.Type.BAZAAR_BUY, itemId, qty, price, 0, 0));
    }

    @Override
    public OrderReport placeSellOffer(String clientOrderId, String itemId, long qty, double price) {
        return report(sim.place(clientOrderId, SimOrder.Type.BAZAAR_SELL, itemId, qty, price, 0, 0));
    }

    @Override
    public OrderReport status(String orderId) {
        return report(sim.get(orderId));
    }

    @Override
    public OrderReport cancel(String orderId) {
        return report(sim.cancel(orderId));
    }

    @Override
    public OrderReport buyAuction(String clientOrderId, String auctionUuid, double price) {
        return report(sim.buyAuction(clientOrderId, auctionUuid, price));
    }

    @Override
    public OrderReport createAuction(String clientOrderId, String itemId, double price, double durationHours, double salesPerDay) {
        return report(sim.place(clientOrderId, SimOrder.Type.AUCTION_LISTING, itemId, 1, price, salesPerDay, durationHours));
    }

    @Override
    public boolean simulated() {
        return true;
    }

    private static OrderReport report(SimOrder o) {
        if (o == null) return new OrderReport(null, OrderState.UNKNOWN, 0, 0, 0, "unknown order");
        OrderState st = switch (o.state) {
            case "OPEN" -> OrderState.OPEN;
            case "FILLED" -> OrderState.FILLED;
            case "CANCELLED" -> OrderState.CANCELLED;
            case "REJECTED" -> OrderState.REJECTED;
            case "EXPIRED" -> OrderState.EXPIRED;
            default -> OrderState.UNKNOWN;
        };
        return new OrderReport(o.id, st, o.quantity, o.filled, o.price, o.message);
    }
}
