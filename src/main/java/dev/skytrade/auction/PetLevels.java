package dev.skytrade.auction;

/** SkyBlock pet experience curve (cumulative per-level requirements with rarity offsets). */
public final class PetLevels {
    private static final int[] XP = {100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400, 440,
            490, 540, 600, 660, 730, 800, 880, 960, 1050, 1150, 1260, 1380, 1510, 1650, 1800, 1960, 2130, 2310, 2500, 2700,
            2920, 3160, 3420, 3700, 4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700,
            16200, 17800, 19500, 21300, 23200, 25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200,
            60400, 64800, 69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700, 119700, 128200, 137200, 146700, 156700,
            167700, 179700, 192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700,
            441700, 476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700,
            1286700, 1386700, 1496700, 1616700, 1746700, 1886700};

    private PetLevels() {}

    public static int offset(String rarity) {
        return switch (rarity == null ? "" : rarity) {
            case "UNCOMMON" -> 6;
            case "RARE" -> 11;
            case "EPIC" -> 16;
            case "LEGENDARY", "MYTHIC" -> 20;
            default -> 0;
        };
    }

    public static int level(String rarity, double exp, int maxLevel) {
        int off = offset(rarity);
        double remaining = exp;
        int level = 1;
        for (int i = off; i < XP.length && level < maxLevel; i++) {
            if (remaining < XP[i]) break;
            remaining -= XP[i];
            level++;
        }
        return level;
    }

    /** Level bucket used in fingerprints: 1-79, 80-89, 90-98, 99, 100 (and 200 for golden dragon style pets). */
    public static String bucket(int level) {
        if (level >= 200) return "200";
        if (level >= 150) return "150+";
        if (level >= 100) return "100";
        if (level >= 99) return "99";
        if (level >= 90) return "90-98";
        if (level >= 80) return "80-89";
        return "<80";
    }
}
