package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账户在某个确定版本下的不可变完整状态快照。
 * 风险计算只能基于快照，不允许读取在线变更中的中间状态。
 */
public record AccountSnapshot(String accountId,
                              long version,
                              BigDecimal cash,
                              List<Position> positions) {

    /** 某标的持仓数量；未持有返回 0（查询默认值，仅用于展示/校验，不代表行情缺失被按零处理）。 */
    public BigDecimal positionOf(String symbol) {
        return positions.stream()
                .filter(p -> p.symbol().equals(symbol))
                .map(Position::quantity)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }
}
