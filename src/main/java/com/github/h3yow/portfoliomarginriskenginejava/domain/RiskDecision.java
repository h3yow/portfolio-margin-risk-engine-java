package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.util.List;

/**
 * 一次风险判定的完整可解释结论。
 */
public record RiskDecision(String accountId,
                           long accountVersion,
                           long quoteVersion,
                           DecisionStatus status,
                           boolean liquidation,
                           List<String> reasons,
                           RiskMetrics metrics,
                           long evaluatedAtMillis) {

    public enum DecisionStatus {
        OK,
        LIQUIDATION
    }
}
