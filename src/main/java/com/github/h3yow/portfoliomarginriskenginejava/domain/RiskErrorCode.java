package com.github.h3yow.portfoliomarginriskenginejava.domain;

/**
 * 可区分的错误/标记分类。风险指标无法定义时作为标记出现在 RiskReport.reasons，
 * 命令被拒绝时作为 RiskEngineException 的错误码。
 */
public enum RiskErrorCode {
    INVALID_ARGUMENT,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ALREADY_EXISTS,
    NEGATIVE_AMOUNT,
    INSUFFICIENT_FUNDS,
    INSUFFICIENT_POSITION,
    SAME_ACCOUNT_TRANSFER,
    MARKET_DATA_MISSING,
    INVALID_PRICE,
    RISK_METRIC_UNDEFINED,
    IDEMPOTENCY_KEY_CONFLICT,
    INTERNAL_ERROR
}
