package dev.skytrade.gui.widget;

import dev.skytrade.gui.Theme;
import dev.skytrade.market.OrderLevel;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.function.DoubleFunction;

/** Line, bar and order-book depth charts drawn with fills. */
public final class Charts {
    private Charts() {}

    public record Series(List<double[]> points, int color, String label) {}

    /** Multi-series line chart. Points are {x, y}; x is typically a timestamp. */
    public static void line(DrawContext ctx, Rect r, List<Series> series, DoubleFunction<String> yLabel, boolean fillFirst, double mx, double my) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        int count = 0;
        for (Series s : series) {
            for (double[] p : s.points()) {
                if (!Double.isFinite(p[1])) continue;
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
                count++;
            }
        }
        if (count < 2) {
            Ui.textCenter(ctx, "Collecting data...", r.x() + r.w() / 2, r.y() + r.h() / 2 - 4, Theme.MUTED);
            return;
        }
        if (maxY - minY < 1e-9) {
            maxY += Math.abs(maxY) * 0.01 + 1;
            minY -= Math.abs(minY) * 0.01 + 1;
        }
        double pad = (maxY - minY) * 0.08;
        minY -= pad;
        maxY += pad;
        int labelW = Math.max(Ui.width(yLabel.apply(maxY)), Ui.width(yLabel.apply(minY))) + 6;
        Rect plot = new Rect(r.x() + labelW, r.y() + 2, r.w() - labelW - 2, r.h() - 6);
        for (int g = 0; g <= 3; g++) {
            int gy = plot.y() + plot.h() * g / 3;
            Ui.hline(ctx, plot.x(), plot.right(), gy, 0x14FFFFFF);
            double v = maxY - (maxY - minY) * g / 3;
            Ui.textRight(ctx, yLabel.apply(v), plot.x() - 4, gy - 4, Theme.FAINT);
        }
        for (int si = 0; si < series.size(); si++) {
            Series s = series.get(si);
            int px = -1, py = -1;
            for (double[] p : s.points()) {
                if (!Double.isFinite(p[1])) continue;
                int x = plot.x() + (int) Math.round((p[0] - minX) / Math.max(1e-9, maxX - minX) * (plot.w() - 1));
                int y = plot.y() + (int) Math.round((maxY - p[1]) / (maxY - minY) * (plot.h() - 1));
                if (si == 0 && fillFirst) ctx.fill(x, y, x + 1, plot.bottom(), Theme.withAlpha(s.color(), 0.10f));
                if (px >= 0) {
                    if (si == 0 && fillFirst) for (int fx = px + 1; fx < x; fx++) {
                        int fy = py + (y - py) * (fx - px) / Math.max(1, x - px);
                        ctx.fill(fx, fy, fx + 1, plot.bottom(), Theme.withAlpha(s.color(), 0.10f));
                    }
                    Ui.line(ctx, px, py, x, y, s.color());
                }
                px = x;
                py = y;
            }
        }
        // Crosshair readout.
        if (plot.contains(mx, my) && !series.isEmpty()) {
            double xv = minX + (mx - plot.x()) / Math.max(1, plot.w() - 1) * (maxX - minX);
            ctx.fill((int) mx, plot.y(), (int) mx + 1, plot.bottom(), 0x30FFFFFF);
            StringBuilder sb = new StringBuilder();
            for (Series s : series) {
                double[] nearest = null;
                for (double[] p : s.points()) if (nearest == null || Math.abs(p[0] - xv) < Math.abs(nearest[0] - xv)) nearest = p;
                if (nearest != null) sb.append(sb.length() > 0 ? "  " : "").append(s.label()).append(' ').append(yLabel.apply(nearest[1]));
            }
            String txt = sb.toString();
            int tw = Ui.width(txt) + 8;
            int tx = Math.min(plot.right() - tw, (int) mx + 6);
            Ui.round(ctx, tx, plot.y() + 2, tw, 12, 2, 0xE0101215);
            Ui.text(ctx, txt, tx + 4, plot.y() + 4, Theme.TEXT);
        }
    }

    /** Vertical bar chart with signed colouring. */
    public static void bars(DrawContext ctx, Rect r, double[] values, String[] labels, DoubleFunction<String> fmt) {
        if (values.length == 0) {
            Ui.textCenter(ctx, "No data", r.x() + r.w() / 2, r.y() + r.h() / 2 - 4, Theme.MUTED);
            return;
        }
        double max = 0;
        for (double v : values) max = Math.max(max, Math.abs(v));
        if (max == 0) max = 1;
        int labelH = labels != null ? 11 : 0;
        int plotH = r.h() - labelH;
        int zero = r.y() + plotH / 2;
        boolean anyNeg = false;
        for (double v : values) if (v < 0) anyNeg = true;
        if (!anyNeg) zero = r.y() + plotH - 1;
        int half = anyNeg ? plotH / 2 - 2 : plotH - 2;
        int bw = Math.max(2, (r.w() - (values.length - 1) * 2) / values.length);
        Ui.hline(ctx, r.x(), r.right(), zero, Theme.BORDER_STRONG);
        for (int i = 0; i < values.length; i++) {
            int bx = r.x() + i * (bw + 2);
            int bh = (int) Math.round(Math.abs(values[i]) / max * half);
            int color = values[i] >= 0 ? Theme.GREEN : Theme.RED;
            if (values[i] >= 0) ctx.fill(bx, zero - bh, bx + bw, zero, color);
            else ctx.fill(bx, zero + 1, bx + bw, zero + 1 + bh, color);
            if (labels != null && labels[i] != null && bw >= Ui.width(labels[i])) Ui.textCenter(ctx, labels[i], bx + bw / 2, r.bottom() - 9, Theme.FAINT);
        }
        Ui.text(ctx, fmt.apply(max), r.x() + 2, r.y(), Theme.FAINT);
    }

    /** Cumulative order book depth: buy orders (green) to the left of the mid, sell offers (red) to the right. */
    public static void depth(DrawContext ctx, Rect r, List<OrderLevel> bids, List<OrderLevel> asks) {
        if (bids.isEmpty() || asks.isEmpty()) {
            Ui.textCenter(ctx, "Order book unavailable", r.x() + r.w() / 2, r.y() + r.h() / 2 - 4, Theme.MUTED);
            return;
        }
        double lo = bids.get(bids.size() - 1).price(), hi = asks.get(asks.size() - 1).price();
        double mid = (bids.get(0).price() + asks.get(0).price()) / 2;
        double span = Math.max(mid - lo, hi - mid);
        lo = mid - span;
        hi = mid + span;
        long cumB = 0, cumA = 0;
        for (OrderLevel l : bids) cumB += l.amount();
        for (OrderLevel l : asks) cumA += l.amount();
        double maxCum = Math.max(1, Math.max(cumB, cumA));
        int plotH = r.h() - 11;
        long c = 0;
        for (OrderLevel l : bids) {
            c += l.amount();
            int x = r.x() + (int) ((l.price() - lo) / (hi - lo) * r.w());
            int xr = r.x() + (int) (((l == bids.get(0) ? mid : prevPrice(bids, l)) - lo) / (hi - lo) * r.w());
            int h = (int) (c / maxCum * plotH);
            ctx.fill(Math.min(x, xr), r.y() + plotH - h, Math.max(x, xr) + 1, r.y() + plotH, 0x803FC97A);
        }
        c = 0;
        for (OrderLevel l : asks) {
            c += l.amount();
            int x = r.x() + (int) ((l.price() - lo) / (hi - lo) * r.w());
            int xl = r.x() + (int) (((l == asks.get(0) ? mid : prevPrice(asks, l)) - lo) / (hi - lo) * r.w());
            int h = (int) (c / maxCum * plotH);
            ctx.fill(Math.min(x, xl), r.y() + plotH - h, Math.max(x, xl) + 1, r.y() + plotH, 0x80E5534B);
        }
        int mx = r.x() + r.w() / 2;
        ctx.fill(mx, r.y(), mx + 1, r.y() + plotH, 0x40FFFFFF);
        Ui.text(ctx, dev.skytrade.util.Fmt.coins(lo), r.x(), r.bottom() - 9, Theme.FAINT);
        Ui.textCenter(ctx, dev.skytrade.util.Fmt.coins(mid), mx, r.bottom() - 9, Theme.MUTED);
        Ui.textRight(ctx, dev.skytrade.util.Fmt.coins(hi), r.right(), r.bottom() - 9, Theme.FAINT);
    }

    private static double prevPrice(List<OrderLevel> side, OrderLevel l) {
        int i = side.indexOf(l);
        return i <= 0 ? l.price() : side.get(i - 1).price();
    }
}
