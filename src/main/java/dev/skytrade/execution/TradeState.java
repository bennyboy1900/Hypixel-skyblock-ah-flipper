package dev.skytrade.execution;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Deterministic trade lifecycle. {@link #allowed()} is the complete transition table; any other
 * transition is rejected by {@link TradeStateMachine}.
 *
 * <pre>
 * IDLE -> ANALYZING -> VALIDATING -> ORDER_PENDING -> BUY_ORDER_ACTIVE -> PARTIALLY_FILLED -> BUY_COMPLETED
 *      -> SELL_ORDER_ACTIVE -> SALE_COMPLETED
 * any non-terminal -> PAUSED (resume returns to the previous state) | ERROR -> RECOVERY -> reconciled state
 * </pre>
 */
public enum TradeState {
    IDLE, ANALYZING, VALIDATING, ORDER_PENDING, BUY_ORDER_ACTIVE, PARTIALLY_FILLED, BUY_COMPLETED,
    SELL_ORDER_ACTIVE, SALE_COMPLETED, CANCELLED, RECOVERY, ERROR, PAUSED;

    private static final Map<TradeState, Set<TradeState>> TABLE = Map.ofEntries(
            entry(IDLE, EnumSet.of(ANALYZING, CANCELLED)),
            entry(ANALYZING, EnumSet.of(VALIDATING, CANCELLED, ERROR)),
            entry(VALIDATING, EnumSet.of(ORDER_PENDING, CANCELLED, ERROR)),
            entry(ORDER_PENDING, EnumSet.of(BUY_ORDER_ACTIVE, BUY_COMPLETED, CANCELLED, ERROR, PAUSED)),
            entry(BUY_ORDER_ACTIVE, EnumSet.of(PARTIALLY_FILLED, BUY_COMPLETED, CANCELLED, ERROR, PAUSED, BUY_ORDER_ACTIVE)),
            entry(PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, BUY_COMPLETED, ERROR, PAUSED)),
            entry(BUY_COMPLETED, EnumSet.of(SELL_ORDER_ACTIVE, ERROR, PAUSED)),
            // BUY_COMPLETED here means "listing expired/cancelled, inventory returned, relist".
            entry(SELL_ORDER_ACTIVE, EnumSet.of(SELL_ORDER_ACTIVE, BUY_COMPLETED, SALE_COMPLETED, ERROR, PAUSED)),
            entry(SALE_COMPLETED, EnumSet.noneOf(TradeState.class)),
            entry(CANCELLED, EnumSet.noneOf(TradeState.class)),
            entry(ERROR, EnumSet.of(RECOVERY, CANCELLED, PAUSED)),
            entry(RECOVERY, EnumSet.of(ORDER_PENDING, BUY_ORDER_ACTIVE, PARTIALLY_FILLED, BUY_COMPLETED, SELL_ORDER_ACTIVE,
                    SALE_COMPLETED, CANCELLED, ERROR, PAUSED)),
            entry(PAUSED, EnumSet.of(ORDER_PENDING, BUY_ORDER_ACTIVE, PARTIALLY_FILLED, BUY_COMPLETED, SELL_ORDER_ACTIVE,
                    RECOVERY, CANCELLED, ERROR))
    );

    public Set<TradeState> allowed() {
        return TABLE.get(this);
    }

    public boolean canTransitionTo(TradeState next) {
        return TABLE.get(this).contains(next);
    }

    public boolean terminal() {
        return this == SALE_COMPLETED || this == CANCELLED;
    }

    /** States in which capital or inventory is committed. */
    public boolean holdsCapital() {
        return switch (this) {
            case ORDER_PENDING, BUY_ORDER_ACTIVE, PARTIALLY_FILLED, BUY_COMPLETED, SELL_ORDER_ACTIVE, ERROR, RECOVERY, PAUSED -> true;
            default -> false;
        };
    }

    /** Maximum time a trade may stay in this state before the timeout policy applies (ms). 0 = no timeout. */
    public long timeoutMs(double cancelAfterHours, double maxHoldHours) {
        return switch (this) {
            case ANALYZING, VALIDATING -> 30_000;
            case ORDER_PENDING -> 120_000;
            case BUY_ORDER_ACTIVE, PARTIALLY_FILLED -> (long) (cancelAfterHours * 3_600_000);
            case SELL_ORDER_ACTIVE -> (long) (maxHoldHours * 3_600_000);
            case ERROR -> 30_000;
            case RECOVERY -> 60_000;
            default -> 0;
        };
    }
}
