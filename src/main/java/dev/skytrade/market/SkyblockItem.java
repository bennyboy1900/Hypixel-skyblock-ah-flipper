package dev.skytrade.market;

import java.util.List;
import java.util.Map;

/**
 * Static item metadata from /v2/resources/skyblock/items.
 *
 * @param gemSlotCosts unlock cost per gemstone slot in API order: slot type -> list of (itemId or "COINS", amount)
 */
public record SkyblockItem(String id, String name, String material, String tier, String category,
                           double npcSellPrice, boolean dungeonItem, int gemstoneSlots, String skinTexture,
                           List<GemSlot> gemSlotCosts) {
    /** One gemstone slot: its type (e.g. COMBAT, SAPPHIRE) and what unlocking it costs. Empty costs = unlocked by default. */
    public record GemSlot(String slotType, Map<String, Integer> costs) {}

    public SkyblockItem(String id, String name, String material, String tier, String category,
                        double npcSellPrice, boolean dungeonItem, int gemstoneSlots, String skinTexture) {
        this(id, name, material, tier, category, npcSellPrice, dungeonItem, gemstoneSlots, skinTexture, List.of());
    }
}
