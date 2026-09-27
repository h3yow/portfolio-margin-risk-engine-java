package com.github.h3yow.portfoliomarginriskenginejava.market;

import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskEngineException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存行情源。
 *
 * <p>缺失行情时 {@link #quote(String)} 返回 empty，风险层据此打出
 * {@link RiskErrorCode#MARKET_DATA_MISSING} 标记，绝不把缺失当作零价；
 * 非正价格在写入入口即拒绝（{@link RiskErrorCode#INVALID_PRICE}），
 * 保证存放在内的报价始终为正。</p>
 */
@Component
public class InMemoryMarketDataProvider implements MarketDataProvider {

    private final ConcurrentHashMap<String, Quote> quotes = new ConcurrentHashMap<>();

    public InMemoryMarketDataProvider() {
    }

    /** 更新（或首次设置）行情；null / 非正价格被明确拒绝。 */
    public void update(Quote quote) {
        if (quote == null || quote.symbol() == null || quote.symbol().isBlank()) {
            throw new RiskEngineException(RiskErrorCode.INVALID_ARGUMENT, "行情或标的为空");
        }
        if (quote.price() == null || quote.price().signum() <= 0) {
            throw new RiskEngineException(RiskErrorCode.INVALID_PRICE,
                    "价格必须为正数: symbol=" + quote.symbol() + ", price=" + quote.price());
        }
        quotes.put(quote.symbol(), quote);
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        return Optional.ofNullable(quotes.get(symbol));
    }
}
