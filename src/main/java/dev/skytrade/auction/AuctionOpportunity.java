package dev.skytrade.auction;

import dev.skytrade.strategy.Opportunity;
import dev.skytrade.valuation.Valuation;

import java.util.List;

/** Buy a BIN listing below fair value and relist it. */
public record AuctionOpportunity(String uuid, String itemId, String name, ItemAttributes attrs, long listingPrice,
                                 Valuation valuation, double grossProfit, double netProfit, double fees,
                                 double risk, double confidence, double liquidity, List<String> upgrades,
                                 List<String> explanation, boolean historyConfirmed, String seller) implements Opportunity {
    public AuctionOpportunity(String uuid, String itemId, String name, ItemAttributes attrs, long listingPrice,
                              Valuation valuation, double grossProfit, double netProfit, double fees, double risk,
                              double confidence, double liquidity, List<String> upgrades, List<String> explanation) {
        this(uuid, itemId, name, attrs, listingPrice, valuation, grossProfit, netProfit, fees, risk, confidence, liquidity,
                upgrades, explanation, true, null);
    }

    /** Flips not confirmed by external price history are shown but never pushed as alerts. */
    @Override public boolean confirmed() { return historyConfirmed; }
    @Override public Kind kind() { return Kind.AUCTION_FLIP; }
    @Override public String key() { return "AH:" + uuid; }
    @Override public double entryPrice() { return listingPrice; }
    @Override public double exitPrice() { return valuation.salePrice(); }
    @Override public long quantity() { return 1; }
    @Override public double holdHours() { return Math.max(0.25, valuation.saleHours()); }
    @Override public String navigationCommand() {
        String u = uuid.length() == 32 ? uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16)
                + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20) : uuid;
        return "/viewauction " + u;
    }
}
