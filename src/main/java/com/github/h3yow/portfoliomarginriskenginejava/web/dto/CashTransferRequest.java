package com.github.h3yow.portfoliomarginriskenginejava.web.dto;

import java.math.BigDecimal;

public record CashTransferRequest(String requestId, String fromAccountId, String toAccountId,
                                  BigDecimal amount) {
}
