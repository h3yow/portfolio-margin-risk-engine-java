package com.github.h3yow.portfoliomarginriskenginejava.web;

import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将可区分错误码映射为 HTTP 响应，错误体携带 code 与可读 message。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RiskEngineException.class)
    public ResponseEntity<ErrorResponse> handle(RiskEngineException ex) {
        return ResponseEntity.status(statusOf(ex.getCode()))
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(RiskErrorCode.INVALID_ARGUMENT.name(), ex.getMessage()));
    }

    private HttpStatus statusOf(RiskErrorCode code) {
        return switch (code) {
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_KEY_CONFLICT, ACCOUNT_ALREADY_EXISTS, SAME_ACCOUNT_TRANSFER ->
                    HttpStatus.CONFLICT;
            case INSUFFICIENT_FUNDS, INSUFFICIENT_POSITION, NEGATIVE_AMOUNT, INVALID_PRICE,
                 MARKET_DATA_MISSING, RISK_METRIC_UNDEFINED, INVALID_ARGUMENT ->
                    HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
