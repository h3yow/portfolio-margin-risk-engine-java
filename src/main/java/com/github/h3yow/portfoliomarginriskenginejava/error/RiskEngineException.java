package com.github.h3yow.portfoliomarginriskenginejava.error;

/**
 * 引擎内所有可分类业务错误的统一异常。
 */
public class RiskEngineException extends RuntimeException {

    private final ErrorCode code;

    public RiskEngineException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
