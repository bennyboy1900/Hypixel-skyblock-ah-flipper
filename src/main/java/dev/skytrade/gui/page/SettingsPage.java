package dev.skytrade.gui.page;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.config.StrategyPreset;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Button;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.TextBox;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.hud.HudEditScreen;
import dev.skytrade.util.Fmt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.*;
import java.util.function.*;

/** Every configurable parameter, grouped into sections. Changes apply immediately and are saved. */
public final class SettingsPage extends Page {
    private enum Unit { COINS, PCT, INT, HOURS, SECONDS, MINUTES, DECIMAL }

    private sealed interface Row permits Section, Toggle, Num, Cycle, Text, Action {}

    private record Section(String title) implements Row {}

    private record Toggle(String label, BooleanSupplier get, Consumer<Boolean> set, String hint) implements Row {}

    private record Num(String label, DoubleSupplier get, DoubleConsumer set, double step, double min, double max, Unit unit) implements Row {}

    private record Cycle(String label, Supplier<String> get, Runnable next) implements Row {}

    private record Text(String label, TextBox box, Consumer<String> commit, boolean masked) implements Row {}

    private record Action(String label, String button, Runnable run, Button.Style style) implements Row {}

    private final List<Row> rows = new ArrayList<>();
    private final List<TextBox> boxes = new ArrayList<>();
    private double scroll;
    private Rect viewport = new Rect(0, 0, 0, 0);
    private String status = "";

    public SettingsPage(SkyTradeScreen screen) {
        super(screen);
        build();
    }

    private SkyTradeConfig c() {
        return core().config();
    }

    private void custom() {
        c().preset = StrategyPreset.CUSTOM;
    }

    private void build() {
        rows.clear();
        boxes.clear();
        rows.add(new Section("Mode & strategy preset"));
        rows.add(new Cycle("Operating mode", () -> c().mode == SkyTradeConfig.TradingMode.LIVE_ANALYSIS ? "Live Market Analysis" : "Private Sandbox Auto Trading",
                () -> c().mode = c().mode == SkyTradeConfig.TradingMode.LIVE_ANALYSIS ? SkyTradeConfig.TradingMode.SANDBOX_AUTO : SkyTradeConfig.TradingMode.LIVE_ANALYSIS));
        rows.add(new Cycle("Strategy preset", () -> c().preset.name(), () -> {
            c().preset = c().preset.next();
            c().preset.apply(c().risk);
        }));

        rows.add(new Section("Trading limits"));
        num("Max capital per trade", () -> c().risk.maxCapitalPerTradePct, v -> c().risk.maxCapitalPerTradePct = v, 0.01, 0.01, 1, Unit.PCT);
        num("Max exposure per item", () -> c().risk.maxExposurePerItemPct, v -> c().risk.maxExposurePerItemPct = v, 0.01, 0.01, 1, Unit.PCT);
        num("Max correlated exposure", () -> c().risk.maxCorrelatedExposurePct, v -> c().risk.maxCorrelatedExposurePct = v, 0.01, 0.05, 1, Unit.PCT);
        num("Emergency cash reserve", () -> c().risk.emergencyReservePct, v -> c().risk.emergencyReservePct = v, 0.01, 0, 0.9, Unit.PCT);
        num("Minimum net profit", () -> c().risk.minNetProfit, v -> c().risk.minNetProfit = v, 10_000, 0, 1e9, Unit.COINS);
        num("Minimum ROI", () -> c().risk.minRoi, v -> c().risk.minRoi = v, 0.005, 0, 5, Unit.PCT);
        num("Minimum liquidity score", () -> c().risk.minLiquidityScore, v -> c().risk.minLiquidityScore = v, 0.05, 0, 1, Unit.DECIMAL);
        num("Minimum confidence", () -> c().risk.minConfidence, v -> c().risk.minConfidence = v, 0.05, 0, 1, Unit.DECIMAL);
        num("Max holding duration", () -> c().risk.maxHoldingHours, v -> c().risk.maxHoldingHours = v, 1, 1, 168, Unit.HOURS);
        num("Max daily simulated loss", () -> c().risk.maxDailyLoss, v -> c().risk.maxDailyLoss = v, 500_000, 0, 1e10, Unit.COINS);
        num("Max simultaneous trades", () -> c().risk.maxSimultaneousTrades, v -> c().risk.maxSimultaneousTrades = (int) v, 1, 1, 50, Unit.INT);
        num("Order slots", () -> c().risk.orderSlots, v -> c().risk.orderSlots = (int) v, 1, 2, 28, Unit.INT);
        num("Max volatility (24h)", () -> c().risk.maxVolatility, v -> c().risk.maxVolatility = v, 0.01, 0.01, 2, Unit.PCT);
        num("Max exit price impact", () -> c().risk.maxPriceImpactPct, v -> c().risk.maxPriceImpactPct = v, 0.005, 0.001, 0.5, Unit.PCT);
        num("Manipulation z-threshold", () -> c().risk.manipulationZ, v -> c().risk.manipulationZ = v, 0.5, 2, 20, Unit.DECIMAL);
        num("Pause after N errors", () -> c().risk.maxConsecutiveErrors, v -> c().risk.maxConsecutiveErrors = (int) v, 1, 1, 50, Unit.INT);
        rows.add(new Toggle("Pause on stale API data", () -> c().risk.pauseOnStaleData, v -> c().risk.pauseOnStaleData = v, ""));
        rows.add(new Toggle("Emergency stop", () -> c().risk.emergencyStop, v -> c().risk.emergencyStop = v, "halts all automated sandbox activity"));
        text("Blacklist (item ids, comma separated)", () -> String.join(",", c().risk.blacklist), v -> c().risk.blacklist = split(v), false);
        text("Whitelist (empty = all)", () -> String.join(",", c().risk.whitelist), v -> c().risk.whitelist = split(v), false);

        rows.add(new Section("Strategies"));
        rows.add(new Toggle("Bazaar flipping", () -> c().strategy.bazaarEnabled, v -> c().strategy.bazaarEnabled = v, ""));
        rows.add(new Toggle("Auction House flipping", () -> c().strategy.auctionEnabled, v -> c().strategy.auctionEnabled = v, ""));
        rows.add(new Toggle("Craft & upgrade flipping", () -> c().strategy.craftEnabled, v -> c().strategy.craftEnabled = v, ""));
        rows.add(new Toggle("Automatic strategy selection", () -> c().strategy.autoSelect, v -> c().strategy.autoSelect = v, "weights by realized returns"));
        num("AH minimum comparable sales", () -> c().strategy.ahMinComparables, v -> c().strategy.ahMinComparables = v, 1, 3, 50, Unit.INT);
        num("AH max expected sale time", () -> c().strategy.ahMaxSaleHours, v -> c().strategy.ahMaxSaleHours = v, 1, 1, 336, Unit.HOURS);
        num("AH undercut", () -> c().strategy.ahUndercut, v -> c().strategy.ahUndercut = v, 0.005, 0, 0.2, Unit.PCT);
        num("Upgrade value recovery", () -> c().strategy.upgradeRecoveryRate, v -> c().strategy.upgradeRecoveryRate = v, 0.05, 0, 1, Unit.PCT);
        num("Min enchant book value", () -> c().strategy.minEnchantValue, v -> c().strategy.minEnchantValue = v, 1_000, 0, 1_000_000, Unit.COINS);
        num("Craft sale haircut", () -> c().strategy.craftSaleHaircut, v -> c().strategy.craftSaleHaircut = v, 0.01, 0.5, 1, Unit.PCT);

        rows.add(new Section("Fees"));
        num("Bazaar tax", () -> c().fees.bazaarTax, v -> c().fees.bazaarTax = v, 0.00125, 0, 0.05, Unit.PCT);
        num("AH claim tax (>1M)", () -> c().fees.ahClaimTax, v -> c().fees.ahClaimTax = v, 0.0025, 0, 0.05, Unit.PCT);
        num("AH listing fee <10M", () -> c().fees.ahListingFeeUnder10M, v -> c().fees.ahListingFeeUnder10M = v, 0.0025, 0, 0.05, Unit.PCT);
        num("AH listing fee 10M-100M", () -> c().fees.ahListingFee10Mto100M, v -> c().fees.ahListingFee10Mto100M = v, 0.0025, 0, 0.05, Unit.PCT);
        num("AH listing fee >100M", () -> c().fees.ahListingFeeOver100M, v -> c().fees.ahListingFeeOver100M = v, 0.0025, 0, 0.05, Unit.PCT);

        rows.add(new Section("Sandbox"));
        rows.add(new Toggle("Autonomous trading", () -> c().sandbox.autoTrade, v -> c().sandbox.autoTrade = v, "only in a verified private environment"));
        rows.add(new Toggle("Reinvest profits", () -> c().sandbox.reinvestProfits, v -> c().sandbox.reinvestProfits = v, ""));
        num("Starting capital", () -> c().sandbox.startingCapital, v -> c().sandbox.startingCapital = v, 10_000_000, 1_000_000, 1e11, Unit.COINS);
        num("Decision interval", () -> c().sandbox.decisionIntervalSeconds, v -> c().sandbox.decisionIntervalSeconds = (int) v, 5, 10, 600, Unit.SECONDS);
        num("Reprice after", () -> c().sandbox.repriceAfterMinutes, v -> c().sandbox.repriceAfterMinutes = v, 5, 1, 240, Unit.MINUTES);
        num("Cancel buy orders after", () -> c().sandbox.cancelAfterHours, v -> c().sandbox.cancelAfterHours = v, 1, 1, 72, Unit.HOURS);
        num("Simulated failure rate", () -> c().sandbox.failureRate, v -> c().sandbox.failureRate = v, 0.005, 0, 0.5, Unit.PCT);
        num("Information delay", () -> c().sandbox.informationDelaySeconds, v -> c().sandbox.informationDelaySeconds = (int) v, 15, 0, 1800, Unit.SECONDS);
        text("Private server allowlist (never Hypixel)", () -> String.join(",", c().sandbox.privateServerAllowlist), v -> c().sandbox.privateServerAllowlist = split(v), false);
        rows.add(new Action("Sandbox environment", "Check", () -> {
            var v = core().sandboxVerdict();
            status = (v.allowed() ? "Allowed: " : "Blocked: ") + v.reason();
        }, Button.Style.SECONDARY));
        rows.add(new Action("Paper portfolio", "Reset", () -> core().resetPaper().thenRun(() -> status = "Paper portfolio reset"), Button.Style.DANGER));

        rows.add(new Section("API"));
        text("Hypixel API key (optional, stored locally)", () -> c().api.apiKey, v -> c().api.apiKey = v.trim(), true);
        num("Bazaar poll interval", () -> c().api.bazaarPollSeconds, v -> c().api.bazaarPollSeconds = (int) v, 10, 20, 600, Unit.SECONDS);
        num("Auction scan interval", () -> c().api.auctionPollSeconds, v -> c().api.auctionPollSeconds = (int) v, 30, 60, 1800, Unit.SECONDS);
        num("Request budget per minute", () -> c().api.maxRequestsPerMinute, v -> c().api.maxRequestsPerMinute = (int) v, 10, 10, 300, Unit.INT);
        num("Stale data threshold", () -> c().api.staleAfterSeconds, v -> c().api.staleAfterSeconds = (int) v, 30, 60, 3600, Unit.SECONDS);
        rows.add(new Toggle("Scan Auction House", () -> c().api.scanAuctions, v -> c().api.scanAuctions = v, "~40 requests per scan"));
        rows.add(new Toggle("Download verified recipes (NEU repo)", () -> c().api.downloadRecipes, v -> c().api.downloadRecipes = v, "from GitHub, daily"));
        rows.add(new Section("Notifications"));
        rows.add(new Toggle("Chat messages", () -> c().notifications.chatMessages, v -> c().notifications.chatMessages = v, ""));
        rows.add(new Toggle("Toasts", () -> c().notifications.toasts, v -> c().notifications.toasts = v, ""));
        rows.add(new Toggle("Opportunity alerts", () -> c().notifications.opportunityAlerts, v -> c().notifications.opportunityAlerts = v, ""));
        num("Alert threshold (profit/h)", () -> c().notifications.opportunityAlertMinProfitPerHour, v -> c().notifications.opportunityAlertMinProfitPerHour = v, 250_000, 0, 1e10, Unit.COINS);
        rows.add(new Toggle("Order fill alerts", () -> c().notifications.orderFillAlerts, v -> c().notifications.orderFillAlerts = v, ""));
        rows.add(new Action("Price alerts (" + c().notifications.priceAlerts.size() + ")", "Clear all", () -> c().notifications.priceAlerts.clear(), Button.Style.SECONDARY));

        rows.add(new Section("HUD"));
        rows.add(new Toggle("Show HUD", () -> c().hud.enabled, v -> c().hud.enabled = v, ""));
        rows.add(new Action("Position", "Edit layout", () -> MinecraftClient.getInstance().setScreen(new HudEditScreen(core(), screen)), Button.Style.SECONDARY));
        num("Scale", () -> c().hud.scale, v -> c().hud.scale = (float) v, 0.1, 0.5, 3, Unit.DECIMAL);
        num("Opacity", () -> c().hud.opacity, v -> c().hud.opacity = (float) v, 0.05, 0.1, 1, Unit.PCT);
        rows.add(new Toggle("Portfolio value", () -> c().hud.showPortfolio, v -> c().hud.showPortfolio = v, ""));
        rows.add(new Toggle("Daily profit", () -> c().hud.showDailyProfit, v -> c().hud.showDailyProfit = v, ""));
        rows.add(new Toggle("Best opportunity", () -> c().hud.showBestOpportunity, v -> c().hud.showBestOpportunity = v, ""));
        rows.add(new Toggle("Active orders", () -> c().hud.showActiveOrders, v -> c().hud.showActiveOrders = v, ""));
        rows.add(new Toggle("API status", () -> c().hud.showApiStatus, v -> c().hud.showApiStatus = v, ""));

        rows.add(new Section("Appearance"));
        rows.add(new Toggle("Animations", () -> c().appearance.animations, v -> c().appearance.animations = v, ""));
        rows.add(new Toggle("Compact tables", () -> c().appearance.compactTables, v -> c().appearance.compactTables = v, ""));

        rows.add(new Section("Database"));
        num("Market history retention", () -> c().database.snapshotRetentionDays, v -> c().database.snapshotRetentionDays = (int) v, 1, 1, 90, Unit.INT);
        num("Transaction retention (days)", () -> c().database.transactionRetentionDays, v -> c().database.transactionRetentionDays = (int) v, 30, 30, 3650, Unit.INT);
        num("Error log retention (days)", () -> c().database.errorRetentionDays, v -> c().database.errorRetentionDays = (int) v, 1, 1, 90, Unit.INT);
        rows.add(new Action("Analytics export", "Export CSV", () -> core().exportCsv().thenAccept(files ->
                status = files == null || files.isEmpty() ? "Export failed" : "Exported " + files.size() + " files to " + files.get(0).getParent()), Button.Style.SECONDARY));
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private void num(String label, DoubleSupplier get, DoubleConsumer set, double step, double min, double max, Unit unit) {
        rows.add(new Num(label, get, v -> {
            set.accept(v);
            if (label.startsWith("Max") || label.startsWith("Min") || label.startsWith("Emergency") || label.startsWith("Order slots")) custom();
        }, step, min, max, unit));
    }

    private void text(String label, Supplier<String> get, Consumer<String> commit, boolean masked) {
        TextBox b = new TextBox(masked ? "not set" : "", 512);
        b.setText(get.get());
        boxes.add(b);
        rows.add(new Text(label, b, commit, masked));
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void onOpen() {
        build();
    }

    private static String format(double v, Unit u) {
        return switch (u) {
            case COINS -> Fmt.coins(v);
            case PCT -> Fmt.pct(v);
            case INT -> String.valueOf((long) Math.round(v));
            case HOURS -> Math.round(v * 10) / 10.0 + "h";
            case SECONDS -> (long) v + "s";
            case MINUTES -> Math.round(v) + "m";
            case DECIMAL -> String.format(Locale.ROOT, "%.2f", v);
        };
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        Ui.card(ctx, a);
        viewport = new Rect(a.x() + 1, a.y() + 1, a.w() - 2, a.h() - 20);
        int rowH = 18;
        int contentH = 0;
        for (Row r : rows) contentH += r instanceof Section ? 22 : rowH;
        scroll = Math.max(0, Math.min(scroll, contentH - viewport.h() + 8));
        ctx.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        int y = viewport.y() + 4 - (int) scroll;
        int labelX = a.x() + 12;
        int controlRight = a.right() - 12;
        boolean shift = MinecraftClient.getInstance().isShiftPressed();
        for (Row r : rows) {
            int h = r instanceof Section ? 22 : rowH;
            boolean visible = y + h > viewport.y() && y < viewport.bottom();
            if (visible) {
                switch (r) {
                    case Section s -> {
                        Ui.text(ctx, s.title().toUpperCase(Locale.ROOT), labelX, y + 9, Theme.BLUE);
                        Ui.hline(ctx, labelX, controlRight, y + 19, Theme.BORDER);
                    }
                    case Toggle t -> {
                        Ui.text(ctx, t.label(), labelX, y + 5, Theme.TEXT);
                        if (!t.hint().isEmpty()) Ui.text(ctx, t.hint(), labelX + Ui.width(t.label()) + 8, y + 5, Theme.FAINT);
                        boolean on = t.get().getAsBoolean();
                        Rect sw = new Rect(controlRight - 24, y + 3, 24, 12);
                        Ui.round(ctx, sw, 6, on ? Theme.BLUE : Theme.BORDER_STRONG);
                        Ui.round(ctx, on ? sw.right() - 11 : sw.x() + 1, sw.y() + 1, 10, 10, 5, 0xFFFFFFFF);
                        if (sw.contains(mx, my) || inViewport(sw)) clickable(sw, () -> t.set().accept(!t.get().getAsBoolean()));
                    }
                    case Num n -> {
                        Ui.text(ctx, n.label(), labelX, y + 5, Theme.TEXT);
                        String val = format(n.get().getAsDouble(), n.unit());
                        int vx = controlRight - 16 - 70;
                        Rect minus = new Rect(vx - 2, y + 1, 16, 15);
                        Rect plus = new Rect(controlRight - 16, y + 1, 16, 15);
                        if (inViewport(minus)) button(ctx, minus, "-", Button.Style.SECONDARY, true, () -> adjust(n, -1, shift));
                        Ui.textCenter(ctx, val, vx + 14 + 35, y + 5, Theme.TEXT);
                        if (inViewport(plus)) button(ctx, plus, "+", Button.Style.SECONDARY, true, () -> adjust(n, 1, shift));
                    }
                    case Cycle cy -> {
                        Ui.text(ctx, cy.label(), labelX, y + 5, Theme.TEXT);
                        String v = cy.get().get();
                        int bw = Button.width(v);
                        Rect br = new Rect(controlRight - bw, y + 1, bw, 15);
                        if (inViewport(br)) button(ctx, br, v, Button.Style.SECONDARY, true, cy.next());
                    }
                    case Text t -> {
                        Ui.text(ctx, t.label(), labelX, y + 5, Theme.TEXT);
                        int bw = Math.min(220, a.w() / 2);
                        Rect br = new Rect(controlRight - bw, y + 1, bw, 15);
                        if (t.masked() && !t.box().focused()) {
                            String m = t.box().text().isEmpty() ? "not set" : "•".repeat(Math.min(16, t.box().text().length()));
                            Ui.round(ctx, br, 3, Theme.BORDER_STRONG);
                            Ui.round(ctx, br.x() + 1, br.y() + 1, br.w() - 2, br.h() - 2, 2, Theme.CARD_ALT);
                            Ui.text(ctx, m, br.x() + 5, br.y() + 4, Theme.MUTED);
                            if (inViewport(br)) clickable(br, () -> focus(t.box()));
                        } else {
                            t.box().render(ctx, br);
                            if (inViewport(br)) clickable(br, () -> focus(t.box()));
                        }
                    }
                    case Action ac -> {
                        Ui.text(ctx, ac.label(), labelX, y + 5, Theme.TEXT);
                        int bw = Button.width(ac.button());
                        Rect br = new Rect(controlRight - bw, y + 1, bw, 15);
                        if (inViewport(br)) button(ctx, br, ac.button(), ac.style(), true, ac.run());
                    }
                }
            }
            y += h;
        }
        ctx.disableScissor();
        Ui.hline(ctx, a.x() + 1, a.right() - 1, a.bottom() - 19, Theme.BORDER);
        Ui.text(ctx, status.isEmpty() ? "Shift-click +/- for 10x steps. Changes save automatically." : status, a.x() + 10, a.bottom() - 13, a.w() - 20, status.isEmpty() ? Theme.FAINT : Theme.TEXT);
    }

    private boolean inViewport(Rect r) {
        return r.y() >= viewport.y() - 2 && r.bottom() <= viewport.bottom() + 2;
    }

    private void focus(TextBox b) {
        for (TextBox o : boxes) if (o != b) o.setFocused(false);
        b.setFocused(true);
    }

    private void adjust(Num n, int dir, boolean shift) {
        double step = n.step() * (shift ? 10 : 1);
        double v = n.get().getAsDouble() + dir * step;
        v = Math.max(n.min(), Math.min(n.max(), v));
        v = Math.round(v / n.step()) * n.step();
        n.set().accept(Math.max(n.min(), Math.min(n.max(), v)));
        core().configManager().save();
    }

    private void commitTexts() {
        for (Row r : rows) if (r instanceof Text t) t.commit().accept(t.box().text());
        core().configManager().save();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean hadFocus = boxes.stream().anyMatch(TextBox::focused);
        for (TextBox b : boxes) b.setFocused(false);
        if (hadFocus) commitTexts();
        boolean handled = super.mouseClicked(mx, my, button);
        if (handled) core().configManager().save();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        scroll -= amount * 24;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int mods) {
        for (TextBox b : boxes) {
            if (b.focused()) {
                b.keyPressed(key, mods);
                if (!b.focused()) commitTexts();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(String chars) {
        for (TextBox b : boxes) if (b.charTyped(chars)) return true;
        return false;
    }
}
