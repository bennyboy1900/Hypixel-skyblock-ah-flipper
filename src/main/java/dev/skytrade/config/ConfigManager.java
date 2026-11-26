package dev.skytrade.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.skytrade.util.Log;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Loads and saves {@link SkyTradeConfig} as JSON with atomic replace-on-write. */
public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private volatile SkyTradeConfig config = new SkyTradeConfig();

    public ConfigManager(Path dir) {
        this.file = dir.resolve("config.json");
    }

    public SkyTradeConfig get() {
        return config;
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            config = new SkyTradeConfig();
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SkyTradeConfig loaded = GSON.fromJson(r, SkyTradeConfig.class);
            config = sanitize(loaded == null ? new SkyTradeConfig() : loaded);
            Log.info("config.loaded", "path", file);
        } catch (IOException | JsonParseException e) {
            Log.error("config.load_failed", e, "path", file);
            try {
                Files.copy(file, file.resolveSibling("config.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            config = new SkyTradeConfig();
            save();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("config.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(config, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.error("config.save_failed", e, "path", file);
        }
    }

    /** Repairs nulls and out-of-range values left by hand-edited files. */
    static SkyTradeConfig sanitize(SkyTradeConfig c) {
        SkyTradeConfig d = new SkyTradeConfig();
        if (c.mode == null) c.mode = d.mode;
        if (c.preset == null) c.preset = d.preset;
        if (c.api == null) c.api = d.api;
        if (c.risk == null) c.risk = d.risk;
        if (c.fees == null) c.fees = d.fees;
        if (c.strategy == null) c.strategy = d.strategy;
        if (c.sandbox == null) c.sandbox = d.sandbox;
        if (c.hud == null) c.hud = d.hud;
        if (c.appearance == null) c.appearance = d.appearance;
        if (c.notifications == null) c.notifications = d.notifications;
        if (c.database == null) c.database = d.database;
        if (c.api.apiKey == null) c.api.apiKey = "";
        if (c.api.baseUrl == null || !c.api.baseUrl.startsWith("https://")) c.api.baseUrl = d.api.baseUrl;
        c.api.bazaarPollSeconds = Math.max(20, c.api.bazaarPollSeconds);
        c.api.auctionPollSeconds = Math.max(60, c.api.auctionPollSeconds);
        c.api.endedPollSeconds = Math.max(30, c.api.endedPollSeconds);
        c.api.maxRequestsPerMinute = Math.max(10, Math.min(300, c.api.maxRequestsPerMinute));
        c.api.auctionPageConcurrency = Math.max(1, Math.min(8, c.api.auctionPageConcurrency));
        c.api.requestTimeoutMs = Math.max(2000, Math.min(60000, c.api.requestTimeoutMs));
        if (c.risk.blacklist == null) c.risk.blacklist = new java.util.ArrayList<>();
        if (c.risk.whitelist == null) c.risk.whitelist = new java.util.ArrayList<>();
        if (c.sandbox.privateServerAllowlist == null) c.sandbox.privateServerAllowlist = new java.util.ArrayList<>();
        if (c.notifications.priceAlerts == null) c.notifications.priceAlerts = new java.util.ArrayList<>();
        c.risk.emergencyReservePct = clamp(c.risk.emergencyReservePct, 0, 0.9);
        c.risk.maxCapitalPerTradePct = clamp(c.risk.maxCapitalPerTradePct, 0.01, 1);
        c.risk.maxExposurePerItemPct = clamp(c.risk.maxExposurePerItemPct, 0.01, 1);
        c.risk.orderSlots = Math.max(1, Math.min(28, c.risk.orderSlots));
        c.hud.scale = (float) clamp(c.hud.scale, 0.5, 3);
        c.hud.opacity = (float) clamp(c.hud.opacity, 0.1, 1);
        c.database.snapshotRetentionDays = Math.max(1, c.database.snapshotRetentionDays);
        return c;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
