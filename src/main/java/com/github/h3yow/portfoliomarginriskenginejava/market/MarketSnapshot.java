package com.github.h3yow.portfoliomarginriskenginejava.market;

import java.util.Map;

/**
 * 全市场行情的不可变快照，带版本号；风险计算基于某一确定版本。
 */
public record MarketSnapshot(long version, long updatedAtMillis, Map<String, Quote> quotes) {
}
