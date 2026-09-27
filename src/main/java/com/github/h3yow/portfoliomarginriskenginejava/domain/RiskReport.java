package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 风险判定结论。
 * defined=false 时 equity/maintenanceMargin/availableMargin 均为 null，
 * 原因见 reasons；liquidation 仅在 defined=true 时有意义。
 * version 为结论所基于的账本状态版本，basis 为人类可读的判定依据。
 */
public record RiskReport(String accountId,
                         long version,
                         long evaluatedAtEpochMs,
                         boolean defined,
                         List<RiskErrorCode> reasons,
                         BigDecimal equity,
                         BigDecimal maintenanceMargin,
                         BigDecimal availableMargin,
                         boolean liquidation,
                         String basis) {
}
