package dev.skytrade.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration. Plain public fields so Gson can (de)serialize it and so the settings page
 * can bind directly to them. Every field has a safe default; missing JSON keys keep the default.
 */
public final class SkyTradeConfig {
    public int schemaVersion = 1;

    public TradingMode mode = TradingMode.LIVE_ANALYSIS;
    public StrategyPreset preset = StrategyPreset.BALANCED;

    public Api api = new Api();
    public Risk risk = new Risk();
    public Fees fees = new Fees();
    public Strategy strategy = new Strategy();
    public Sandbox sandbox = new Sandbox();
    public Hud hud = new Hud();
    public Appearance appearance = new Appearance();
    public Notifications notifications = new Notifications();
    public Database database = new Database();
    public External external = new External();
    public Assist assist = new Assist();

    public enum TradingMode { LIVE_ANALYSIS, SANDBOX_AUTO }

    public static final class Api {
        /** Optional Hypixel API key. Only sent as a header, never logged or exported. */
        public String apiKey = "";
        public String baseUrl = "https://api.hypixel.net";
        public int requestTimeoutMs = 15_000;
        public int bazaarPollSeconds = 60;
        public int auctionPollSeconds = 90;
        public int endedPollSeconds = 60;
        public int itemsRefreshMinutes = 360;
        /** Conservative client-side request budget (requests per minute). */
        public int maxRequestsPerMinute = 100;
        /** Max concurrent auction page requests during a full scan. */
        public int auctionPageConcurrency = 4;
        /** Snapshots older than this are flagged stale and trading pauses. */
        public int staleAfterSeconds = 300;
        public boolean scanAuctions = true;
        public boolean downloadRecipes = true;
        public String recipeRepoUrl = "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.zip";
    }

    /** External 7-day price history used to confirm valuations and catch manipulated prices. */
    public static final class External {
        public boolean enabled = true;
        /** Coflnet SkyBlock API. Only item tags (and pet rarity) are sent. */
        public String baseUrl = "https://sky.coflnet.com";
        /** Shared budget for every Coflnet request. Coflnet blocks IPs that go over its limit, so keep this low. */
        public int maxRequestsPerMinute = 20;
        public int cacheMinutes = 30;
        /** Bulk lowest-BIN list for all items (one request). */
        public int bulkRefreshMinutes = 10;
        /** Coflnet's free flip lists (bazaar spread, crafts, Kat), re-checked locally, shown on the Deals page. */
        public boolean dealsEnabled = true;
        public int dealsRefreshMinutes = 5;
        public double dealsMinProfit = 200_000;
        /**
         * Before enough local sales exist, value a listing from identical competing listings, confirmed by the
         * 7-day history. Lower confidence; deep discounts are still rejected.
         */
        public boolean marketFallback = true;
        /** Identical listings from other sellers needed for the market fallback. */
        public int minCompetingListings = 2;
        /** Without 7-day history an AH flip is shown as "unconfirmed", gets lower confidence and no alert. */
        public boolean requireConfirmation = true;
        /** Items with fewer sales than this in 7 days are rejected as illiquid. */
        public int minWeeklySales = 10;
        /** 24h average above this multiple of the 7-day median counts as a pump; fair value is scaled back. */
        public double pumpRatio = 1.35;
        /** Weekly trend below this (e.g. -15%) counts as a falling market; fair value is reduced. */
        public double crashTrend = -0.15;
        /** Clean local value above this multiple of the 7-day high is rejected as manipulated comparables. */
        public double maxOverWeeklyHigh = 1.5;
        /** Discounts above this need an exact match and confirmed history, or the listing is rejected. */
        public double deepDiscount = 0.5;
        /** How many top AH candidates per scan may request history. */
        public int candidatesPerScan = 30;
    }

    /** Manual assist: everything is triggered by you; the mod never clicks or sends anything by itself. */
    public static final class Assist {
        /** Flip alerts in chat get a clickable [VIEW] that puts /viewauction into your chat box (you press Enter). */
        public boolean clickableAlerts = true;
        /** Minimum net profit for a chat flip alert. */
        public double alertMinProfit = 500_000;
        /** Alert only flips confirmed by 7-day history. */
        public boolean alertOnlyConfirmed = true;
    }

    public static final class Risk {
        public double maxCapitalPerTradePct = 0.20;
        public double maxExposurePerItemPct = 0.25;
        public double maxCorrelatedExposurePct = 0.40;
        public double emergencyReservePct = 0.10;
        public double minNetProfit = 50_000;
        public double minRoi = 0.02;
        public double minLiquidityScore = 0.35;
        public double maxHoldingHours = 12;
        public double maxDailyLoss = 5_000_000;
        public int maxSimultaneousTrades = 8;
        public double maxVolatility = 0.12;
        public double maxPriceImpactPct = 0.03;
        public int orderSlots = 14;
        public double minConfidence = 0.45;
        public int maxConsecutiveErrors = 5;
        public boolean pauseOnStaleData = true;
        public boolean emergencyStop = false;
        public List<String> blacklist = new ArrayList<>();
        public List<String> whitelist = new ArrayList<>();
        /** Robust z-score above which a price move is considered suspicious. */
        public double manipulationZ = 6.0;
    }

    public static final class Fees {
        /** Bazaar sell-offer tax (1.25% base; lower with the Bazaar Flipper account upgrade). */
        public double bazaarTax = 0.0125;
        /** Auction House claim tax on sales above 1M. */
        public double ahClaimTax = 0.01;
        public double ahListingFeeUnder10M = 0.01;
        public double ahListingFee10Mto100M = 0.02;
        public double ahListingFeeOver100M = 0.025;
        public double ahDurationFee = 1_200; // ~2 day BIN duration fee approximation
    }

    public static final class Strategy {
        public boolean bazaarEnabled = true;
        public boolean auctionEnabled = true;
        public boolean craftEnabled = true;
        public boolean autoSelect = true;
        public double ahMinComparables = 5;
        public double ahMaxSaleHours = 48;
        public double ahUndercut = 0.01;
        public double upgradeRecoveryRate = 0.55;
        /** Enchant books cheaper than this are ignored when valuing an item (all others are counted). */
        public double minEnchantValue = 5_000;
        public double craftSaleHaircut = 0.95;
        public int bazaarTopN = 250;
        public int auctionTopN = 150;
    }

    public static final class Sandbox {
        public double startingCapital = 100_000_000;
        public boolean autoTrade = false;
        public boolean reinvestProfits = true;
        public int decisionIntervalSeconds = 30;
        public double repriceAfterMinutes = 20;
        public double cancelAfterHours = 6;
        /** Fraction of orders that fail at random in the simulator (models network/game errors). */
        public double failureRate = 0.01;
        /** Simulated information delay applied to the trader's view of the market. */
        public int informationDelaySeconds = 60;
        public long rngSeed = 1337;
        /** Additional non-Hypixel server addresses the user explicitly verified as private test servers. */
        public List<String> privateServerAllowlist = new ArrayList<>();
    }

    public static final class Hud {
        public boolean enabled = true;
        public int x = 4;
        public int y = 4;
        public float scale = 1.0f;
        public float opacity = 0.75f;
        public boolean showPortfolio = true;
        public boolean showDailyProfit = true;
        public boolean showBestOpportunity = true;
        public boolean showActiveOrders = true;
        public boolean showApiStatus = true;
    }

    public static final class Appearance {
        public boolean animations = true;
        public int accentColor = 0xFF4C8DFF;
        public boolean compactTables = false;
        /** 0 = follow the game GUI scale. */
        public int forceGuiScale = 0;
    }

    public static final class Notifications {
        public boolean chatMessages = true;
        public boolean toasts = true;
        public boolean opportunityAlerts = true;
        public double opportunityAlertMinProfitPerHour = 2_000_000;
        public boolean orderFillAlerts = true;
        public List<PriceAlert> priceAlerts = new ArrayList<>();
    }

    public static final class PriceAlert {
        public String productId = "";
        /** "ABOVE" or "BELOW". */
        public String direction = "BELOW";
        public double price;
        public boolean useBuyPrice = true;
        public boolean triggered;
    }

    public static final class Database {
        public int snapshotRetentionDays = 14;
        public int transactionRetentionDays = 365;
        public int errorRetentionDays = 7;
        /** Minimum seconds between persisted bazaar history points per product. */
        public int historyIntervalSeconds = 300;
    }
}
