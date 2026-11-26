package dev.skytrade.auction;

import dev.skytrade.config.SkyTradeConfig;

/** Auction House fee model: tiered BIN listing fee, duration fee, and 1% claim tax above 1M. */
public final class AuctionFees {
    private AuctionFees() {}

    public static double listingFee(double price, SkyTradeConfig.Fees f) {
        double rate = price < 10_000_000 ? f.ahListingFeeUnder10M : price < 100_000_000 ? f.ahListingFee10Mto100M : f.ahListingFeeOver100M;
        return price * rate + f.ahDurationFee;
    }

    public static double claimTax(double price, SkyTradeConfig.Fees f) {
        return price > 1_000_000 ? price * f.ahClaimTax : 0;
    }

    /** Coins received after listing at {@code price} and claiming. */
    public static double netProceeds(double price, SkyTradeConfig.Fees f) {
        return price - listingFee(price, f) - claimTax(price, f);
    }
}
