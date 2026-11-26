package dev.skytrade.auction;

import java.util.List;
import java.util.Map;

/**
 * Decoded, market-relevant characteristics of a SkyBlock item.
 *
 * @param skyblockId       ExtraAttributes id, or {@code PET_<TYPE>} for pets
 * @param extras           other applied, purchasable upgrades as item id -> quantity (Jalapeno Book, Wood
 *                         Singularity, Transmission Tuners, drill parts, power scroll, dye, enrichment, ...)
 * @param unlockedGemSlots gemstone slot names the player unlocked (e.g. {@code COMBAT_0}); unlocks cost coins and gems
 * @param variant          what makes items with the same id different products (cake year, the enchant on an
 *                         enchanted book, rune type and level, party hat colour, shiny, potion type), or null
 */
public record ItemAttributes(
        String skyblockId,
        String rarity,
        String reforge,
        Map<String, Integer> enchantments,
        String ultimateEnchant,
        int ultimateLevel,
        boolean recombobulated,
        int dungeonStars,
        int masterStars,
        Map<String, String> gems,
        int hotPotatoBooks,
        int fumingPotatoBooks,
        List<String> abilityScrolls,
        boolean artOfWar,
        String petType,
        int petLevel,
        String petHeldItem,
        int petCandyUsed,
        String skin,
        Map<String, Integer> attributes,
        int count,
        String displayName,
        Map<String, Integer> extras,
        List<String> unlockedGemSlots,
        String variant
) {
    public ItemAttributes(String skyblockId, String rarity, String reforge, Map<String, Integer> enchantments,
                          String ultimateEnchant, int ultimateLevel, boolean recombobulated, int dungeonStars, int masterStars,
                          Map<String, String> gems, int hotPotatoBooks, int fumingPotatoBooks, List<String> abilityScrolls,
                          boolean artOfWar, String petType, int petLevel, String petHeldItem, int petCandyUsed, String skin,
                          Map<String, Integer> attributes, int count, String displayName, Map<String, Integer> extras,
                          List<String> unlockedGemSlots) {
        this(skyblockId, rarity, reforge, enchantments, ultimateEnchant, ultimateLevel, recombobulated, dungeonStars, masterStars,
                gems, hotPotatoBooks, fumingPotatoBooks, abilityScrolls, artOfWar, petType, petLevel, petHeldItem, petCandyUsed,
                skin, attributes, count, displayName, extras, unlockedGemSlots, null);
    }

    public ItemAttributes(String skyblockId, String rarity, String reforge, Map<String, Integer> enchantments,
                          String ultimateEnchant, int ultimateLevel, boolean recombobulated, int dungeonStars, int masterStars,
                          Map<String, String> gems, int hotPotatoBooks, int fumingPotatoBooks, List<String> abilityScrolls,
                          boolean artOfWar, String petType, int petLevel, String petHeldItem, int petCandyUsed, String skin,
                          Map<String, Integer> attributes, int count, String displayName) {
        this(skyblockId, rarity, reforge, enchantments, ultimateEnchant, ultimateLevel, recombobulated, dungeonStars, masterStars,
                gems, hotPotatoBooks, fumingPotatoBooks, abilityScrolls, artOfWar, petType, petLevel, petHeldItem, petCandyUsed,
                skin, attributes, count, displayName, Map.of(), List.of());
    }

    public boolean isPet() {
        return petType != null;
    }
}
