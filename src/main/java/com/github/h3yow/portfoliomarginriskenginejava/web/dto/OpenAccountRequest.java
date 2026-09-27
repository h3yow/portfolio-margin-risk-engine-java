package com.github.h3yow.portfoliomarginriskenginejava.web.dto;

import java.math.BigDecimal;

public record OpenAccountRequest(String requestId, String accountId, BigDecimal initialCash) {
}
