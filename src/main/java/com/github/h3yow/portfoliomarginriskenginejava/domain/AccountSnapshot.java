package com.github.h3yow.portfoliomarginriskenginejava.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 某一确定时刻账户资金 + 持仓的完整不可变快照，带单调递增的版本号。
 *
 * <p>不变量（资金/持仓守恒的基础）：</p>
 * <ul>
 *   <li>cash 可正可负：负值代表保证金贷款（对券商的欠款），多头模型下由杠杆买入产生；
 *       是否允许新增贷款由开仓时的初始保证金校验决定；</li>
 *   <li>positions 中每条持仓 qty &gt; 0（多头模型，不允许负持仓），avgCost &gt; 0；</li>
 *   <li>每次状态迁移要么整体产生新版本，要么完全不生效，不存在部分更新。</li>
 * </ul>
 */
public record AccountSnapshot(String accountId,
                              long version,
                              long updatedAtMillis,
                              BigDecimal cash,
                              Map<String, Position> positions) {

    public AccountSnapshot {
        // 防御性拷贝 + 不可变性：快照一旦发布永不改变
        positions = positions == null ? Map.of() : Map.copyOf(positions);
    }

    /** 版本 0 的初始空账户。 */
    public static AccountSnapshot empty(String accountId) {
        return new AccountSnapshot(accountId, 0L, System.currentTimeMillis(),
                BigDecimal.ZERO, Map.of());
    }

    public Position position(String symbol) {
        return positions.get(symbol);
    }
}
