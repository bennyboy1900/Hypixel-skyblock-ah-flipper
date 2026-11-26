package dev.skytrade.bazaar;

import dev.skytrade.config.SkyTradeConfig;

/** Bazaar fee model. Buy orders are free; sell offers and instant sells pay the bazaar tax. */
public final class BazaarFees {
    private BazaarFees() {}

    public static double sellProceeds(double price, long qty, SkyTradeConfig.Fees f) {
        return price * qty * (1 - f.bazaarTax);
    }

    public static double tax(double price, long qty, SkyTradeConfig.Fees f) {
        return price * qty * f.bazaarTax;
    }

    /** Hypixel price tick: orders are placed in 0.1 coin increments. */
    public static double tick() {
        return 0.1;
    }

    /** Maximum units per bazaar order. */
    public static final long MAX_ORDER_UNITS = 71_680;
}
