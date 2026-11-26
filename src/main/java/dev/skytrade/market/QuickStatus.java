package dev.skytrade.market;

/**
 * Hypixel bazaar quick_status. Naming follows Hypixel: {@code sell*} fields describe the instant-sell
 * side (i.e. resting buy orders), {@code buy*} fields describe the instant-buy side (resting sell offers).
 * {@code sellMovingWeek} is therefore the 7-day volume that filled buy orders.
 */
public record QuickStatus(double sellPrice, long sellVolume, long sellMovingWeek, int sellOrders,
                          double buyPrice, long buyVolume, long buyMovingWeek, int buyOrders) {
    public static final QuickStatus EMPTY = new QuickStatus(0, 0, 0, 0, 0, 0, 0, 0);
}
