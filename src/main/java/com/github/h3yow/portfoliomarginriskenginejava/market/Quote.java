package com.github.h3yow.portfoliomarginriskenginejava.market;

import java.math.BigDecimal;

/** 标的最新行情。 */
public record Quote(String symbol, BigDecimal price, long quoteTimeEpochMs) {
}
