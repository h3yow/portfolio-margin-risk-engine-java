package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.math.BigDecimal;

/** 某账户对单一标的的持仓数量（可为负，代表空头）。 */
public record Position(String symbol, BigDecimal quantity) {
}
