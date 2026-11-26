package dev.skytrade.simulation;

/** A resting order inside the simulated market. Mutable; owned by {@link MarketSimulator}. */
public final class SimOrder {
    public enum Type { BAZAAR_BUY, BAZAAR_SELL, AUCTION_LISTING }

    public String id;
    public String clientId;
    public Type type;
    public String itemId;
    public long quantity;
    public long filled;
    public double price;
    public long createdAt;
    public long expiresAt;
    public String state = "OPEN";
    public double salesPerDay;
    /** Fractional fill carried between ticks so slow markets still fill eventually. */
    public double fillCarry;
    public String message = "";

    public boolean open() {
        return "OPEN".equals(state);
    }
}
