package dev.skytrade.gui;

import dev.skytrade.market.ItemCatalog;
import dev.skytrade.market.SkyblockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Maps SkyBlock items (whose materials use legacy 1.8 names) to vanilla item stacks for icons. Render thread only. */
public final class ItemIcons {
    private static final Map<String, String> LEGACY = new HashMap<>();
    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    static {
        String[][] m = {
                {"SKULL_ITEM", "player_head"}, {"INK_SACK", "ink_sac"}, {"WOOD_SWORD", "wooden_sword"}, {"WOOD_AXE", "wooden_axe"},
                {"WOOD_PICKAXE", "wooden_pickaxe"}, {"WOOD_SPADE", "wooden_shovel"}, {"WOOD_HOE", "wooden_hoe"},
                {"GOLD_SWORD", "golden_sword"}, {"GOLD_AXE", "golden_axe"}, {"GOLD_PICKAXE", "golden_pickaxe"},
                {"GOLD_SPADE", "golden_shovel"}, {"GOLD_HOE", "golden_hoe"}, {"GOLD_HELMET", "golden_helmet"},
                {"GOLD_CHESTPLATE", "golden_chestplate"}, {"GOLD_LEGGINGS", "golden_leggings"}, {"GOLD_BOOTS", "golden_boots"},
                {"IRON_SPADE", "iron_shovel"}, {"DIAMOND_SPADE", "diamond_shovel"}, {"STONE_SPADE", "stone_shovel"},
                {"RAW_FISH", "cod"}, {"COOKED_FISH", "cooked_cod"}, {"SULPHUR", "gunpowder"}, {"NETHER_STALK", "nether_wart"},
                {"CARROT_ITEM", "carrot"}, {"POTATO_ITEM", "potato"}, {"WATCH", "clock"}, {"EXP_BOTTLE", "experience_bottle"},
                {"LEASH", "lead"}, {"FIREWORK", "firework_rocket"}, {"FIREWORK_CHARGE", "firework_star"}, {"SNOW_BALL", "snowball"},
                {"BOOK_AND_QUILL", "writable_book"}, {"EMPTY_MAP", "map"}, {"WOOL", "white_wool"}, {"STAINED_GLASS", "white_stained_glass"},
                {"STAINED_GLASS_PANE", "white_stained_glass_pane"}, {"THIN_GLASS", "glass_pane"}, {"LOG", "oak_log"}, {"LOG_2", "acacia_log"},
                {"WOOD", "oak_planks"}, {"RED_ROSE", "poppy"}, {"YELLOW_FLOWER", "dandelion"}, {"SEEDS", "wheat_seeds"},
                {"MUSHROOM_SOUP", "mushroom_stew"}, {"PORK", "porkchop"}, {"GRILLED_PORK", "cooked_porkchop"}, {"RAW_BEEF", "beef"},
                {"RAW_CHICKEN", "chicken"}, {"ENDER_STONE", "end_stone"}, {"NETHER_BRICK_ITEM", "nether_brick"},
                {"IRON_BARDING", "iron_horse_armor"}, {"GOLD_BARDING", "golden_horse_armor"}, {"DIAMOND_BARDING", "diamond_horse_armor"},
                {"FIREBALL", "fire_charge"}, {"REDSTONE_COMPARATOR", "comparator"}, {"WEB", "cobweb"}, {"LONG_GRASS", "short_grass"},
                {"DOUBLE_PLANT", "sunflower"}, {"HUGE_MUSHROOM_1", "brown_mushroom_block"}, {"HUGE_MUSHROOM_2", "red_mushroom_block"},
                {"SAPLING", "oak_sapling"}, {"MYCEL", "mycelium"}, {"STEP", "stone_slab"}, {"WOOD_STEP", "oak_slab"},
                {"SMOOTH_BRICK", "stone_bricks"}, {"QUARTZ_ORE", "nether_quartz_ore"}, {"GLOWING_REDSTONE_ORE", "redstone_ore"},
                {"MELON_BLOCK", "melon"}, {"MONSTER_EGG", "pig_spawn_egg"}, {"SKULL", "skeleton_skull"}, {"CAKE_BLOCK", "cake"},
                {"BED", "red_bed"}, {"BOAT", "oak_boat"}, {"WOOD_DOOR", "oak_door"}, {"WOODEN_DOOR", "oak_door"},
                {"FENCE", "oak_fence"}, {"FENCE_GATE", "oak_fence_gate"}, {"TRAP_DOOR", "oak_trapdoor"}, {"CARPET", "white_carpet"},
                {"STAINED_CLAY", "white_terracotta"}, {"HARD_CLAY", "terracotta"}, {"SOIL", "farmland"}, {"IRON_FENCE", "iron_bars"},
                {"WORKBENCH", "crafting_table"}, {"BURNING_FURNACE", "furnace"}, {"PISTON_BASE", "piston"},
                {"PISTON_STICKY_BASE", "sticky_piston"}, {"ENCHANTMENT_TABLE", "enchanting_table"}, {"ENDER_PORTAL_FRAME", "end_portal_frame"},
                {"COMMAND", "command_block"}, {"SPECKLED_MELON", "glistering_melon_slice"}, {"MELON", "melon_slice"},
                {"RAW_FISH:1", "salmon"}, {"PRISMARINE_CRYSTALS", "prismarine_crystals"}, {"GOLD_RECORD", "music_disc_13"},
                {"GREEN_RECORD", "music_disc_cat"}, {"RECORD_3", "music_disc_blocks"}, {"SIGN", "oak_sign"}, {"BANNER", "white_banner"},
                {"MOB_SPAWNER", "spawner"}, {"NOTE_BLOCK", "note_block"}, {"SILVER_FENCE", "iron_bars"}, {"ENDER_CHEST", "ender_chest"},
                {"INK", "ink_sac"}, {"CLAY_BRICK", "brick"}, {"NETHER_WARTS", "nether_wart"}, {"CHAINMAIL_HELMET", "chainmail_helmet"},
        };
        for (String[] e : m) LEGACY.put(e[0], e[1]);
    }

    private ItemIcons() {}

    public static ItemStack forId(String skyblockId, ItemCatalog catalog) {
        ItemStack cached = CACHE.get(skyblockId);
        if (cached != null) return cached;
        ItemStack s = resolve(skyblockId, catalog);
        if (CACHE.size() > 4000) CACHE.clear();
        CACHE.put(skyblockId, s);
        return s;
    }

    private static ItemStack resolve(String id, ItemCatalog catalog) {
        if (id == null) return new ItemStack(Items.PAPER);
        if (id.startsWith("ENCHANTMENT_")) return new ItemStack(Items.ENCHANTED_BOOK);
        if (id.startsWith("ESSENCE_")) return new ItemStack(Items.PLAYER_HEAD);
        if (id.startsWith("PET_")) return new ItemStack(Items.PLAYER_HEAD);
        SkyblockItem item = catalog == null ? null : catalog.get(id);
        if (item != null && item.material() != null) {
            Item it = byName(item.material());
            if (it != null) return new ItemStack(it);
        }
        Item guess = byName(id.replace("ENCHANTED_", ""));
        if (guess != null) return new ItemStack(guess);
        return new ItemStack(Items.PAPER);
    }

    private static Item byName(String material) {
        String key = material.toUpperCase(Locale.ROOT);
        String name = LEGACY.getOrDefault(key, key.toLowerCase(Locale.ROOT));
        Identifier idf = Identifier.tryParse("minecraft", name);
        if (idf == null) return null;
        return Registries.ITEM.getOptionalValue(idf).filter(i -> i != Items.AIR).orElse(null);
    }
}
