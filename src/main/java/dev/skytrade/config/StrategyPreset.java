package dev.skytrade.config;

/** Risk presets. CUSTOM leaves the user's values untouched. */
public enum StrategyPreset {
    CONSERVATIVE, BALANCED, AGGRESSIVE, CUSTOM;

    public void apply(SkyTradeConfig.Risk r) {
        switch (this) {
            case CONSERVATIVE -> set(r, 0.10, 0.15, 0.25, 0.25, 100_000, 0.04, 0.55, 6, 2_000_000, 5, 0.06, 0.015, 0.60);
            case BALANCED -> set(r, 0.20, 0.25, 0.40, 0.10, 50_000, 0.02, 0.35, 12, 5_000_000, 8, 0.12, 0.03, 0.45);
            case AGGRESSIVE -> set(r, 0.35, 0.40, 0.60, 0.05, 20_000, 0.01, 0.20, 24, 15_000_000, 14, 0.25, 0.06, 0.30);
            case CUSTOM -> { }
        }
    }

    private static void set(SkyTradeConfig.Risk r, double perTrade, double perItem, double correlated, double reserve,
                            double minProfit, double minRoi, double minLiq, double maxHold, double maxLoss,
                            int maxTrades, double maxVol, double maxImpact, double minConf) {
        r.maxCapitalPerTradePct = perTrade;
        r.maxExposurePerItemPct = perItem;
        r.maxCorrelatedExposurePct = correlated;
        r.emergencyReservePct = reserve;
        r.minNetProfit = minProfit;
        r.minRoi = minRoi;
        r.minLiquidityScore = minLiq;
        r.maxHoldingHours = maxHold;
        r.maxDailyLoss = maxLoss;
        r.maxSimultaneousTrades = maxTrades;
        r.maxVolatility = maxVol;
        r.maxPriceImpactPct = maxImpact;
        r.minConfidence = minConf;
    }

    public StrategyPreset next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
