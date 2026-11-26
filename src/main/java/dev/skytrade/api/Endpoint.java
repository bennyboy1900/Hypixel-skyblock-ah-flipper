package dev.skytrade.api;

/** Hypixel public API endpoints used by the mod. None of them require an API key. */
public enum Endpoint {
    BAZAAR("/v2/skyblock/bazaar"),
    AUCTIONS("/v2/skyblock/auctions"),
    AUCTIONS_ENDED("/v2/skyblock/auctions_ended"),
    ITEMS("/v2/resources/skyblock/items");

    public final String path;

    Endpoint(String path) {
        this.path = path;
    }
}
