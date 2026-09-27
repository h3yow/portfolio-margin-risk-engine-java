package com.github.h3yow.portfoliomarginriskenginejava.web.dto;

import java.math.BigDecimal;

public record QuoteUpdateRequest(String symbol, BigDecimal price) {
}
