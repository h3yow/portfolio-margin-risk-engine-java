package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 风险指标：权益、维持保证金、可用保证金，以及追溯所用的版本号。
 */
public record RiskMetrics(long accountVersion,
                          long quoteVersion,
                          BigDecimal equity,
                          BigDecimal maintenanceMargin,
                          BigDecimal availableMargin,
                          List<PositionValuation> valuations) {
}
