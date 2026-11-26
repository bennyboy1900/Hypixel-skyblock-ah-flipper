package dev.skytrade;

import dev.skytrade.commands.SkyTradeCommands;
import dev.skytrade.execution.sandbox.EnvironmentProbe;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.hud.HudOverlay;
import dev.skytrade.notifications.Notification;
import dev.skytrade.util.Log;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Fabric client entrypoint: wires the headless core into Minecraft (screens, HUD, keys, commands). */
public final class SkyTradeClient implements ClientModInitializer {
    private static SkyTradeCore core;
    private static volatile SkyTradeScreen.Nav pendingScreen;
    private static final ClientProbe PROBE = new ClientProbe();

    private KeyBinding openKey, hudKey, pauseKey, bestFlipKey;
    /** Debug option: -Dskytrade.openOnStart=DASHBOARD opens the terminal over the title screen once. */
    private static final String OPEN_ON_START = System.getProperty("skytrade.openOnStart");
    private boolean openedOnStart;

    public static SkyTradeCore core() {
        return core;
    }

    /** Opens the terminal on the next client tick (safe to call from command handlers). */
    public static void openScreen(SkyTradeScreen.Nav nav) {
        pendingScreen = nav;
    }

    @Override
    public void onInitializeClient() {
        core = new SkyTradeCore(FabricLoader.getInstance().getConfigDir().resolve("skytrade"), PROBE);
        core.start();

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("skytrade", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.skytrade.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        hudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.skytrade.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.skytrade.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        bestFlipKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.skytrade.best_flip", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> SkyTradeCommands.register(dispatcher));
        HudElementRegistry.addLast(Identifier.of("skytrade", "hud"), (ctx, tick) -> HudOverlay.render(ctx, core, false));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> core.close());
        Log.info("skytrade.initialized", "dir", core.directory());
    }

    private void tick(MinecraftClient client) {
        PROBE.update(client);
        while (openKey.wasPressed()) {
            if (client.currentScreen == null) client.setScreen(new SkyTradeScreen(core, SkyTradeScreen.Nav.DASHBOARD));
        }
        while (hudKey.wasPressed()) {
            core.config().hud.enabled = !core.config().hud.enabled;
            core.configManager().save();
        }
        while (pauseKey.wasPressed()) {
            if (core.risk().breaker().manuallyPaused()) {
                core.resume();
                chat("Sandbox trading resumed.");
            } else {
                core.pause();
                chat("Sandbox trading paused.");
            }
        }
        while (bestFlipKey.wasPressed()) {
            if (client.currentScreen == null) suggestBestFlip(client);
        }
        if (OPEN_ON_START != null && !openedOnStart && client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen) {
            openedOnStart = true;
            try {
                client.setScreen(new SkyTradeScreen(core, SkyTradeScreen.Nav.valueOf(OPEN_ON_START)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        SkyTradeScreen.Nav nav = pendingScreen;
        if (nav != null) {
            pendingScreen = null;
            client.setScreen(new SkyTradeScreen(core, nav));
        }
        deliverNotifications(client);
    }

    private static void deliverNotifications(MinecraftClient client) {
        var cfg = core.config().notifications;
        for (int i = 0; i < 2; i++) {
            Notification n = core.notifications().poll();
            if (n == null) break;
            if (cfg.chatMessages && client.inGameHud != null) {
                Formatting f = switch (n.level()) {
                    case SUCCESS -> Formatting.GREEN;
                    case WARNING -> Formatting.YELLOW;
                    case ERROR -> Formatting.RED;
                    default -> Formatting.GRAY;
                };
                MutableText line = Text.literal("[SkyTrade] ").formatted(Formatting.BLUE)
                        .append(Text.literal(n.title() + ": ").formatted(Formatting.WHITE)).append(Text.literal(n.message()).formatted(f));
                if (n.suggestCommand() != null) line.append(Text.literal(" ")).append(suggestButton(n.suggestCommand()));
                client.inGameHud.getChatHud().addMessage(line);
            }
            if (cfg.toasts && n.level() != Notification.Level.INFO) {
                SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("SkyTrade: " + n.title()), Text.literal(n.message()));
            }
        }
    }

    /**
     * A clickable [VIEW] that only types the command into the chat box. Nothing is sent until the player
     * presses Enter themselves, so every action on the server stays a manual one.
     */
    static MutableText suggestButton(String command) {
        return Text.literal("[VIEW]").formatted(Formatting.GREEN, Formatting.BOLD).styled(st -> st
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(command + "\nClick to put it in your chat box, then press Enter."))));
    }

    /** Opens the chat box with the best confirmed AH flip's /viewauction command typed in (not sent). */
    private static void suggestBestFlip(MinecraftClient client) {
        var best = core.results().auctions().stream()
                .filter(o -> o.confirmed() && o.netProfit() > 0)
                .findFirst().orElse(null);
        if (best == null) {
            chat("No confirmed AH flip right now.");
            return;
        }
        chat(best.name() + ": buy " + dev.skytrade.util.Fmt.coins(best.listingPrice()) + ", relist "
                + dev.skytrade.util.Fmt.coins(best.valuation().salePrice()) + ", net " + dev.skytrade.util.Fmt.signedCoins(best.netProfit())
                + ". Press Enter to open it.");
        client.setScreen(new ChatScreen(best.navigationCommand(), false));
    }

    /** Posts a chat line from any thread. */
    public static void chat(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal("[SkyTrade] ").formatted(Formatting.BLUE).append(Text.literal(message)));
        });
    }

    /** Environment snapshot captured on the client thread, read by the sandbox guard on other threads. */
    static final class ClientProbe implements EnvironmentProbe {
        private volatile Location location = Location.UNKNOWN;
        private volatile String address;
        private volatile String brand;

        void update(MinecraftClient c) {
            try {
                if (c.world == null) {
                    location = Location.MAIN_MENU;
                    address = null;
                    brand = null;
                } else if (c.isInSingleplayer()) {
                    location = Location.SINGLEPLAYER;
                    address = null;
                    brand = c.getNetworkHandler() != null ? c.getNetworkHandler().getBrand() : null;
                } else {
                    ServerInfo info = c.getCurrentServerEntry();
                    location = Location.MULTIPLAYER;
                    address = info != null ? info.address : null;
                    brand = c.getNetworkHandler() != null ? c.getNetworkHandler().getBrand() : null;
                }
            } catch (RuntimeException e) {
                location = Location.UNKNOWN;
            }
        }

        @Override
        public Location location() {
            return location;
        }

        @Override
        public String serverAddress() {
            return address;
        }

        @Override
        public String serverBrand() {
            return brand;
        }
    }
}
