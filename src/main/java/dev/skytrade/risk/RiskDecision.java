package dev.skytrade.risk;

import java.util.List;

public record RiskDecision(boolean approved, List<String> reasons) {
    public static RiskDecision ok() {
        return new RiskDecision(true, List.of());
    }
}
