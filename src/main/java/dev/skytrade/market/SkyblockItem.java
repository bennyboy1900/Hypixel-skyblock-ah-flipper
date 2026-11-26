package dev.skytrade.market;

/** Static item metadata from /v2/resources/skyblock/items. */
public record SkyblockItem(String id, String name, String material, String tier, String category,
                           double npcSellPrice, boolean dungeonItem, int gemstoneSlots, String skinTexture) {}
