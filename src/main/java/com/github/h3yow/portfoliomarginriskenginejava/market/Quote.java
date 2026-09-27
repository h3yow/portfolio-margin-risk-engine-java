package com.github.h3yow.portfoliomarginriskenginejava.market;

import java.math.BigDecimal;

/**
 * 行情快照（不可变）。价格非正为非法数据，由调用方显式拒绝。
 */
public record Quote(String symbol, BigDecimal price, long updatedAtMillis) {
}
