package dev.skytrade.gui;

/** Design tokens for the trading terminal (ARGB; text colours must keep full alpha). */
public final class Theme {
    private Theme() {}

    public static final int BG = 0xF0121418;
    public static final int SIDEBAR = 0xFF0E1013;
    public static final int CARD = 0xFF1B1E24;
    public static final int CARD_HOVER = 0xFF22262E;
    public static final int CARD_ALT = 0xFF181B20;
    public static final int BORDER = 0xFF2A2F38;
    public static final int BORDER_STRONG = 0xFF3A404B;
    public static final int TEXT = 0xFFE6E8EC;
    public static final int MUTED = 0xFF8A919E;
    public static final int FAINT = 0xFF5C6370;
    public static final int GREEN = 0xFF3FC97A;
    public static final int GREEN_DIM = 0x333FC97A;
    public static final int RED = 0xFFE5534B;
    public static final int RED_DIM = 0x33E5534B;
    public static final int YELLOW = 0xFFE3B341;
    public static final int BLUE = 0xFF4C8DFF;
    public static final int BLUE_DIM = 0x334C8DFF;
    public static final int SELECTION = 0x334C8DFF;

    public static int signed(double v) {
        return v > 0 ? GREEN : v < 0 ? RED : MUTED;
    }

    /** 0..1 risk: green -> yellow -> red. */
    public static int risk(double r) {
        return r < 0.33 ? GREEN : r < 0.6 ? YELLOW : RED;
    }

    /** 0..1 quality score (confidence, liquidity): red -> yellow -> green. */
    public static int quality(double q) {
        return q >= 0.66 ? GREEN : q >= 0.4 ? YELLOW : RED;
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (color & 0xFFFFFF);
    }
}
