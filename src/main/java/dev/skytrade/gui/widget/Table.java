package dev.skytrade.gui.widget;

import dev.skytrade.gui.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Sortable, filterable, scrollable table with optional item icon column. */
public final class Table<T> {
    public enum Align { LEFT, RIGHT }

    public static final class Column<T> {
        final String title;
        final int minWidth;
        final float weight;
        final Align align;
        final Function<T, String> text;
        final Function<T, Integer> color;
        final Comparator<T> sort;
        Function<T, ItemStack> icon;
        /** Lower priority columns are hidden first when the table is too narrow. */
        int priority = 5;

        public Column(String title, int minWidth, float weight, Align align, Function<T, String> text,
                      Function<T, Integer> color, Comparator<T> sort) {
            this.title = title;
            this.minWidth = minWidth;
            this.weight = weight;
            this.align = align;
            this.text = text;
            this.color = color;
            this.sort = sort;
        }

        public Column<T> icon(Function<T, ItemStack> icon) {
            this.icon = icon;
            this.priority = 100;
            return this;
        }

        public Column<T> priority(int p) {
            this.priority = p;
            return this;
        }
    }

    private final List<Column<T>> columns = new ArrayList<>();
    private List<T> source = List.of();
    private List<T> view = List.of();
    private Predicate<T> filter = t -> true;
    private int sortColumn = -1;
    private boolean descending = true;
    private double scroll;
    private T selected;
    private Consumer<T> onSelect = t -> { };
    private Rect bounds = new Rect(0, 0, 0, 0);
    private int rowHeight = 16;
    private boolean dirty = true;
    private String emptyText = "No data";

    public Table<T> column(Column<T> c) {
        columns.add(c);
        return this;
    }

    public Table<T> rowHeight(int h) { rowHeight = h; return this; }
    public Table<T> onSelect(Consumer<T> c) { onSelect = c; return this; }
    public Table<T> emptyText(String s) { emptyText = s; return this; }

    public Table<T> sortBy(int col, boolean desc) {
        sortColumn = col;
        descending = desc;
        dirty = true;
        return this;
    }

    /** Sets rows; cheap to call every frame (re-sorts only when the list instance changes). */
    public void setRows(List<T> rows) {
        if (rows != source) {
            source = rows;
            dirty = true;
        }
    }

    public void setFilter(Predicate<T> f) {
        filter = f;
        dirty = true;
    }

    public List<T> view() {
        refresh();
        return view;
    }

    public T selected() { return selected; }

    private void refresh() {
        if (!dirty) return;
        List<T> v = new ArrayList<>();
        for (T t : source) if (filter.test(t)) v.add(t);
        if (sortColumn >= 0 && sortColumn < columns.size() && columns.get(sortColumn).sort != null) {
            Comparator<T> c = columns.get(sortColumn).sort;
            v.sort(descending ? c.reversed() : c);
        }
        view = v;
        dirty = false;
    }

    /** Column widths; hidden columns (dropped by priority when space is short) get width 0. */
    private int[] widths(int total) {
        int n = columns.size();
        boolean[] visible = new boolean[n];
        java.util.Arrays.fill(visible, true);
        int fixed = 0;
        for (Column<T> c : columns) fixed += c.minWidth;
        while (fixed > total) {
            int drop = -1;
            for (int i = 0; i < n; i++) {
                if (visible[i] && (drop < 0 || columns.get(i).priority < columns.get(drop).priority)) drop = i;
            }
            if (drop < 0 || columns.get(drop).priority >= 100) break;
            visible[drop] = false;
            fixed -= columns.get(drop).minWidth;
        }
        float weights = 0;
        for (int i = 0; i < n; i++) if (visible[i]) weights += columns.get(i).weight;
        int extra = Math.max(0, total - fixed);
        int[] w = new int[n];
        int used = 0, last = -1;
        for (int i = 0; i < n; i++) {
            if (!visible[i]) continue;
            w[i] = columns.get(i).minWidth + (weights > 0 ? (int) (extra * columns.get(i).weight / weights) : 0);
            used += w[i];
            last = i;
        }
        if (last >= 0) w[last] += Math.max(0, total - used);
        return w;
    }

    public void render(DrawContext ctx, Rect r, double mx, double my) {
        refresh();
        bounds = r;
        Ui.card(ctx, r);
        int inner = r.w() - 12;
        int[] w = widths(inner);
        int header = 16;
        int x = r.x() + 6;
        for (int i = 0; i < columns.size(); i++) {
            if (w[i] == 0) continue;
            Column<T> c = columns.get(i);
            String t = c.title + (i == sortColumn ? (descending ? " v" : " ^") : "");
            int color = i == sortColumn ? Theme.TEXT : Theme.MUTED;
            if (c.align == Align.RIGHT) Ui.textRight(ctx, Ui.fit(t, w[i] - 4), x + w[i] - 4, r.y() + 5, color);
            else Ui.text(ctx, t, x, r.y() + 5, w[i] - 4, color);
            x += w[i];
        }
        Ui.hline(ctx, r.x() + 1, r.right() - 1, r.y() + header, Theme.BORDER);

        Rect body = new Rect(r.x() + 1, r.y() + header + 1, r.w() - 2, r.h() - header - 2);
        int contentH = view.size() * rowHeight;
        double maxScroll = Math.max(0, contentH - body.h());
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        if (view.isEmpty()) {
            Ui.textCenter(ctx, emptyText, r.x() + r.w() / 2, body.y() + body.h() / 2 - 4, Theme.MUTED);
            return;
        }
        ctx.enableScissor(body.x(), body.y(), body.right(), body.bottom());
        int first = (int) (scroll / rowHeight);
        int last = Math.min(view.size(), first + body.h() / rowHeight + 2);
        for (int i = first; i < last; i++) {
            T row = view.get(i);
            int ry = body.y() + i * rowHeight - (int) scroll;
            boolean hover = body.contains(mx, my) && my >= ry && my < ry + rowHeight;
            if (row == selected) ctx.fill(body.x(), ry, body.right(), ry + rowHeight, Theme.SELECTION);
            else if (hover) ctx.fill(body.x(), ry, body.right(), ry + rowHeight, Theme.CARD_HOVER);
            else if (i % 2 == 1) ctx.fill(body.x(), ry, body.right(), ry + rowHeight, 0x08FFFFFF);
            int cx = r.x() + 6;
            int ty = ry + (rowHeight - 8) / 2;
            for (int c = 0; c < columns.size(); c++) {
                if (w[c] == 0) continue;
                Column<T> col = columns.get(c);
                int tx = cx;
                int avail = w[c] - 4;
                if (col.icon != null) {
                    ItemStack st = col.icon.apply(row);
                    float s = rowHeight >= 16 ? 0.875f : 0.625f;
                    Ui.item(ctx, st, cx, ry + (rowHeight - Math.round(16 * s)) / 2, s);
                    tx += Math.round(16 * s) + 3;
                    avail -= Math.round(16 * s) + 3;
                }
                String s = col.text.apply(row);
                int color = col.color == null ? Theme.TEXT : col.color.apply(row);
                if (col.align == Align.RIGHT) Ui.textRight(ctx, Ui.fit(s, avail), cx + w[c] - 4, ty, color);
                else Ui.text(ctx, s, tx, ty, avail, color);
                cx += w[c];
            }
        }
        ctx.disableScissor();
        if (maxScroll > 0) {
            int trackH = body.h();
            int thumbH = Math.max(12, (int) (trackH * (body.h() / (double) contentH)));
            int thumbY = body.y() + (int) ((trackH - thumbH) * (scroll / maxScroll));
            Ui.round(ctx, r.right() - 5, thumbY, 3, thumbH, 1, Theme.BORDER_STRONG);
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!bounds.contains(mx, my) || button != 0) return false;
        int header = 16;
        if (my < bounds.y() + header) {
            int[] w = widths(bounds.w() - 12);
            int x = bounds.x() + 6;
            for (int i = 0; i < columns.size(); i++) {
                if (w[i] > 0 && mx >= x && mx < x + w[i] && columns.get(i).sort != null) {
                    if (sortColumn == i) descending = !descending;
                    else {
                        sortColumn = i;
                        descending = true;
                    }
                    dirty = true;
                    return true;
                }
                x += w[i];
            }
            return true;
        }
        int idx = (int) ((my - bounds.y() - header - 1 + scroll) / rowHeight);
        if (idx >= 0 && idx < view.size()) {
            selected = view.get(idx);
            onSelect.accept(selected);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!bounds.contains(mx, my)) return false;
        scroll -= amount * rowHeight * 3;
        return true;
    }
}
