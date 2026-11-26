package dev.skytrade;

import dev.skytrade.auction.Fingerprint;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.auction.ItemDecoder;
import dev.skytrade.auction.PetLevels;
import dev.skytrade.valuation.UpgradeComponent;
import dev.skytrade.valuation.UpgradeValuer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemDecoderTest {
    private static String sample(String k) {
        return TestData.json("item_samples.json").get(k).getAsString();
    }

    @Test
    void decodesUpgradedHyperion() throws Exception {
        ItemAttributes a = ItemDecoder.decodeUncached(sample("hyperion"), "LEGENDARY");
        assertEquals("HYPERION", a.skyblockId());
        assertEquals("MYTHIC", a.rarity(), "rarity comes from the (recombobulated) lore line");
        assertTrue(a.recombobulated());
        assertEquals(5, a.dungeonStars());
        assertEquals(0, a.masterStars());
        assertEquals(10, a.hotPotatoBooks());
        assertEquals(3, a.abilityScrolls().size());
        assertEquals("ultimate_wise", a.ultimateEnchant());
        assertEquals(5, a.ultimateLevel());
        assertEquals("heroic", a.reforge());
        assertFalse(a.isPet());
        var comps = UpgradeValuer.components(a);
        assertTrue(comps.stream().anyMatch(c -> c.itemId().equals("RECOMBOBULATOR_3000")));
        assertTrue(comps.stream().anyMatch(c -> c.itemId().equals("IMPLOSION_SCROLL")));
        assertTrue(comps.stream().anyMatch(c -> c.itemId().equals("ENCHANTMENT_ULTIMATE_WISE_5")));
        String strict = Fingerprint.strict(a);
        assertTrue(strict.startsWith("HYPERION|MYTHIC|s5"), strict);
        assertTrue(strict.contains("scrolls3"));
    }

    @Test
    void decodesPet() throws Exception {
        ItemAttributes a = ItemDecoder.decodeUncached(sample("pet"), "LEGENDARY");
        assertTrue(a.isPet());
        assertEquals("PET_MOLE", a.skyblockId());
        assertEquals("LEGENDARY", a.rarity());
        assertEquals(1, a.petLevel());
        assertEquals("PET_MOLE|LEGENDARY", Fingerprint.base(a));
    }

    @Test
    void decodesGemstones() throws Exception {
        ItemAttributes a = ItemDecoder.decodeUncached(sample("halberd"), "LEGENDARY");
        assertEquals("AXE_OF_THE_SHREDDED", a.skyblockId());
        assertEquals("FINE_JASPER_GEM", a.gems().get("JASPER_0"));
        assertTrue(UpgradeValuer.components(a).contains(new UpgradeComponent("FINE_JASPER_GEM", 1, "Gemstone")));
    }

    @Test
    void cacheReturnsSameResultAndToleratesGarbage() {
        ItemDecoder d = new ItemDecoder(10);
        assertTrue(d.decode("x", "not base64!!", null).isEmpty());
        assertTrue(d.decode("h", sample("hyperion"), null).isPresent());
        assertSame(d.decode("h", "ignored-because-cached", null).get(), d.decode("h", "ignored", null).get());
    }

    @Test
    void petLevelCurve() {
        assertEquals(1, PetLevels.level("LEGENDARY", 0, 100));
        assertEquals(100, PetLevels.level("LEGENDARY", 25_353_230, 100));
        assertEquals(100, PetLevels.level("LEGENDARY", 1e12, 100), "capped at max level");
        assertTrue(PetLevels.level("COMMON", 5_624_785, 100) >= 99);
        assertEquals("99", PetLevels.bucket(99));
    }
}
