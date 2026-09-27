package com.github.h3yow.portfoliomarginriskenginejava.web.dto;

import java.math.BigDecimal;

public record PositionTransferRequest(String requestId, String fromAccountId, String toAccountId,
                                      String symbol, BigDecimal quantity) {
}
