package dev.skytrade.auction;

import java.util.List;
import java.util.Map;

/**
 * Decoded, market-relevant characteristics of a SkyBlock item.
 *
 * @param skyblockId ExtraAttributes id, or {@code PET_<TYPE>} for pets
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
        String displayName
) {
    public boolean isPet() {
        return petType != null;
    }
}
