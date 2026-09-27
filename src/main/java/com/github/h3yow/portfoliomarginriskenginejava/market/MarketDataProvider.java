package com.github.h3yow.portfoliomarginriskenginejava.market;

import java.util.Optional;

/** 行情来源：缺失行情必须返回 empty，由调用方产生可区分标记，禁止按零价处理。 */
public interface MarketDataProvider {

    Optional<Quote> quote(String symbol);
}
