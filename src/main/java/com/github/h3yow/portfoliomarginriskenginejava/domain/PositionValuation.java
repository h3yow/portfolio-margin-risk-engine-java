package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.math.BigDecimal;

/**
 * 单条持仓在风险计算中的估值明细，作为判定依据的一部分。
 */
public record PositionValuation(String symbol,
                                BigDecimal qty,
                                BigDecimal price,
                                BigDecimal marketValue,
                                BigDecimal marginRate,
                                BigDecimal maintenanceMargin) {
}
