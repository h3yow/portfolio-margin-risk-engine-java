package com.github.h3yow.portfoliomarginriskenginejava.error;

/**
 * 可区分的错误分类，任何异常情形都不得静默按零/默认值处理。
 */
public enum ErrorCode {
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ALREADY_EXISTS,
    INVALID_ARGUMENT,
    VERSION_CONFLICT,
    INSUFFICIENT_FUNDS,
    INSUFFICIENT_POSITION,
    MARKET_DATA_MISSING,
    INVALID_PRICE,
    MARGIN_RATE_MISSING
}
