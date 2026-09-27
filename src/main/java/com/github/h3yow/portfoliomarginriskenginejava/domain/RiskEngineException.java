package com.github.h3yow.portfoliomarginriskenginejava.domain;

/** 业务错误：携带可区分的 {@link RiskErrorCode}，不得静默按默认值处理。 */
public class RiskEngineException extends RuntimeException {

    private final RiskErrorCode code;

    public RiskEngineException(RiskErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RiskErrorCode getCode() {
        return code;
    }
}
