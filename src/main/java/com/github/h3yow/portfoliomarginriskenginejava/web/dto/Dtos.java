package com.github.h3yow.portfoliomarginriskenginejava.web.dto;

import java.math.BigDecimal;

/**
 * Web 层请求/响应 DTO 集合（record 不可变）。
 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateAccountRequest(String accountId) {
    }

    public record TransferRequest(String accountId,
                                  String requestId,
                                  String type,
                                  BigDecimal amount,
                                  Long expectedVersion) {
    }

    public record AdjustPositionRequest(String accountId,
                                        String requestId,
                                        String symbol,
                                        String side,
                                        BigDecimal qty,
                                        BigDecimal price,
                                        Long expectedVersion) {
    }

    public record QuoteRequest(String symbol, BigDecimal price) {
    }

    public record MarginRateRequest(String symbol, BigDecimal rate) {
    }

    public record ErrorResponse(String code, String message) {
    }
}
