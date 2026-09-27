package com.github.h3yow.portfoliomarginriskenginejava.web;

import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把可分类业务错误映射为<b>可区分</b>的 HTTP 状态码与错误码，调用方可以据此做不同处理，
 * 而不会把行情缺失、价格非法、余额不足、版本冲突等混为一谈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RiskEngineException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleRiskEngine(RiskEngineException ex) {
        HttpStatus status = statusOf(ex.getCode());
        log.warn("业务错误 code={} status={} message={}", ex.getCode(), status, ex.getMessage());
        return ResponseEntity.status(status)
                .body(new Dtos.ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorResponse("INTERNAL_ERROR", ex.getMessage()));
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;                    // 404
            case ACCOUNT_ALREADY_EXISTS -> HttpStatus.CONFLICT;               // 409
            case VERSION_CONFLICT -> HttpStatus.CONFLICT;                     // 409
            case INVALID_ARGUMENT, INVALID_PRICE -> HttpStatus.BAD_REQUEST;   // 400
            case INSUFFICIENT_FUNDS, INSUFFICIENT_POSITION ->
                    HttpStatus.UNPROCESSABLE_ENTITY;                          // 422
            case MARKET_DATA_MISSING, MARGIN_RATE_MISSING ->
                    HttpStatus.SERVICE_UNAVAILABLE;                           // 503
        };
    }
}
