package dev.skytrade.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.skytrade.SkyTradeClient;
import dev.skytrade.SkyTradeCore;
import dev.skytrade.api.ApiStatus;
import dev.skytrade.api.Endpoint;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.util.Fmt;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side /skytrade commands. None of them send anything to the server. */
public final class SkyTradeCommands {
    private SkyTradeCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("skytrade")
                .executes(c -> open(SkyTradeScreen.Nav.DASHBOARD))
                .then(literal("bazaar").executes(c -> open(SkyTradeScreen.Nav.BAZAAR)))
                .then(literal("ah").executes(c -> open(SkyTradeScreen.Nav.AUCTIONS)))
                .then(literal("portfolio").executes(c -> open(SkyTradeScreen.Nav.PORTFOLIO)))
                .then(literal("settings").executes(c -> open(SkyTradeScreen.Nav.SETTINGS)))
                .then(literal("status").executes(SkyTradeCommands::status))
                .then(literal("pause").executes(c -> {
                    core().pause();
                    msg(c, "Sandbox trading paused.", Formatting.YELLOW);
                    return 1;
                }))
                .then(literal("resume").executes(c -> {
                    core().resume();
                    msg(c, "Sandbox trading resumed (emergency stop cleared).", Formatting.GREEN);
                    return 1;
                }))
                .then(literal("resetpaper")
                        .executes(c -> {
                            msg(c, "This deletes all paper/sandbox trades and resets capital. Run /skytrade resetpaper confirm", Formatting.YELLOW);
                            return 1;
                        })
                        .then(literal("confirm").executes(c -> {
                            core().resetPaper().thenRun(() -> SkyTradeClient.chat("Paper portfolio reset to " + Fmt.coins(core().config().sandbox.startingCapital) + "."));
                            return 1;
                        })))
                .then(literal("export").executes(c -> {
                    msg(c, "Exporting analytics to CSV...", Formatting.GRAY);
                    core().exportCsv().whenComplete((files, err) -> {
                        if (err != null || files == null || files.isEmpty()) SkyTradeClient.chat("Export failed: " + (err == null ? "database unavailable" : err.getMessage()));
                        else SkyTradeClient.chat("Exported " + files.size() + " CSV files to " + files.get(0).getParent());
                    });
                    return 1;
                }))
                .then(literal("help").executes(SkyTradeCommands::help)));
    }

    private static SkyTradeCore core() {
        return SkyTradeClient.core();
    }

    private static int open(SkyTradeScreen.Nav nav) {
        SkyTradeClient.openScreen(nav);
        return 1;
    }

    private static void msg(CommandContext<FabricClientCommandSource> c, String s, Formatting f) {
        c.getSource().sendFeedback(Text.literal("[SkyTrade] ").formatted(Formatting.BLUE).append(Text.literal(s).formatted(f)));
    }

    private static int status(CommandContext<FabricClientCommandSource> c) {
        SkyTradeCore core = core();
        long now = System.currentTimeMillis();
        ApiStatus st = core.market().status();
        msg(c, "API " + st.overall() + (st.rateLimitRemaining() >= 0 ? " (rate limit remaining " + st.rateLimitRemaining() + ")" : ""), Formatting.WHITE);
        for (Endpoint e : Endpoint.values()) {
            ApiStatus.EndpointState s = st.get(e);
            String age = s.dataTimestamp > 0 ? "data age " + Fmt.duration(now - s.dataTimestamp) : s.lastSuccessAt > 0 ? "fetched " + Fmt.age(s.lastSuccessAt, now) : "no data yet";
            msg(c, "  " + e.name().toLowerCase() + ": " + age + (s.consecutiveFailures > 0 ? ", failing: " + s.lastError : ""),
                    s.consecutiveFailures > 0 ? Formatting.RED : Formatting.GRAY);
        }
        msg(c, "  7-day price history: " + core.external().status(), Formatting.GRAY);
        var pv = core.portfolio();
        msg(c, "Paper portfolio " + Fmt.coins(pv.totalValue()) + " (cash " + Fmt.coins(pv.cash()) + ", today " + Fmt.signedCoins(pv.dailyProfit())
                + ", " + pv.activeTrades().size() + " active)", Formatting.WHITE);
        msg(c, "Mode: " + core.config().mode + " | trader: " + core.traderStatus().reason(), Formatting.GRAY);
        var v = core.sandboxVerdict();
        msg(c, "Sandbox environment: " + (v.allowed() ? "allowed - " : "blocked - ") + v.reason(), v.allowed() ? Formatting.GREEN : Formatting.YELLOW);
        var best = core.results().best();
        if (best != null) msg(c, "Best: " + best.name() + " " + Fmt.coins(best.adjustedProfitPerHour()) + "/h  -> " + best.navigationCommand(), Formatting.GREEN);
        return 1;
    }

    private static int help(CommandContext<FabricClientCommandSource> c) {
        msg(c, "/skytrade [bazaar|ah|portfolio|settings|status|pause|resume|resetpaper|export]", Formatting.WHITE);
        msg(c, "Manual Hypixel navigation (type these yourself): /bz <item>, /ah, /viewauction <uuid>", Formatting.GRAY);
        msg(c, "SkyTrade never sends commands or trades on your behalf on Hypixel.", Formatting.GRAY);
        return 1;
    }
}
