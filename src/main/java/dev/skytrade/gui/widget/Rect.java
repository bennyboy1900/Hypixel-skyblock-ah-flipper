package dev.skytrade.gui.widget;

public record Rect(int x, int y, int w, int h) {
    public int right() { return x + w; }
    public int bottom() { return y + h; }
    public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    public Rect inset(int d) { return new Rect(x + d, y + d, Math.max(0, w - 2 * d), Math.max(0, h - 2 * d)); }
    public Rect offset(int dx, int dy) { return new Rect(x + dx, y + dy, w, h); }

    /** Splits horizontally into {@code n} equal columns with {@code gap} between them. */
    public Rect[] columns(int n, int gap) {
        Rect[] out = new Rect[n];
        int cw = (w - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) out[i] = new Rect(x + i * (cw + gap), y, i == n - 1 ? w - i * (cw + gap) : cw, h);
        return out;
    }

    public Rect top(int height) { return new Rect(x, y, w, Math.min(h, height)); }
    public Rect belowTop(int height, int gap) { return new Rect(x, y + height + gap, w, Math.max(0, h - height - gap)); }
    public Rect left(int width) { return new Rect(x, y, Math.min(w, width), h); }
    public Rect rightOf(int width, int gap) { return new Rect(x + width + gap, y, Math.max(0, w - width - gap), h); }
}
