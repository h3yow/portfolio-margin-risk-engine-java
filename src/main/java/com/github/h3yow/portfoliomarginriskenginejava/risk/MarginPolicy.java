package com.github.h3yow.portfoliomarginriskenginejava.risk;

import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维持保证金率策略：MM = 市值 * marginRate。
 *
 * <p>按标的单独配置；未配置时 {@link #requireRate(String)} 显式抛
 * {@link ErrorCode#MARGIN_RATE_MISSING}，绝不默认取 0 而导致风险指标虚高。</p>
 */
@Component
public class MarginPolicy {

    /** 保证金率取值区间 (0, 1]：例如 0.2 表示维持保证金为市值的 20%。 */
    private static final BigDecimal MAX_RATE = BigDecimal.ONE;

    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();

    public void setRate(String symbol, BigDecimal rate) {
        if (symbol == null || symbol.isBlank()) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "symbol 不能为空");
        }
        if (rate == null || rate.signum() <= 0 || rate.compareTo(MAX_RATE) > 0) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT,
                    "保证金率必须在 (0, 1] 区间内，symbol=" + symbol + "，rate=" + rate);
        }
        rates.put(symbol, rate);
    }

    /** 返回保证金率；未配置时显式失败，不做零值兜底。 */
    public BigDecimal requireRate(String symbol) {
        BigDecimal rate = rates.get(symbol);
        if (rate == null) {
            throw new RiskEngineException(ErrorCode.MARGIN_RATE_MISSING,
                    "标的缺少维持保证金率配置，symbol=" + symbol);
        }
        return rate;
    }
}
