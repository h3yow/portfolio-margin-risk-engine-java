package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.math.BigDecimal;

/**
 * 持仓（不可变）。当前模型仅支持多头，qty 恒为正。
 */
public record Position(String symbol, BigDecimal qty, BigDecimal avgCost) {
}
