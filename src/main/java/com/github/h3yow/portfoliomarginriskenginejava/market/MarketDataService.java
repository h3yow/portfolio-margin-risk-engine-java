package com.github.h3yow.portfoliomarginriskenginejava.market;

import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地内存行情源。
 *
 * <p>所有报价放入同一张不可变 {@link MarketSnapshot}，通过 {@link AtomicReference}
 * 原子替换；读侧永远拿到某个确定版本的完整行情，不会读到逐条更新的中间状态。</p>
 */
@Service
public class MarketDataService {

    private final AtomicReference<MarketSnapshot> ref =
            new AtomicReference<>(new MarketSnapshot(0L, System.currentTimeMillis(), Map.of()));

    /** 读取当前完整行情快照。 */
    public MarketSnapshot snapshot() {
        return ref.get();
    }

    /**
     * 写入/更新一条报价。价格为 null 或非正属于非法行情，显式拒绝，
     * 绝不可被后续风险计算静默当作 0 使用。
     */
    public MarketSnapshot putQuote(String symbol, BigDecimal price) {
        if (symbol == null || symbol.isBlank()) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "symbol 不能为空");
        }
        if (price == null || price.signum() <= 0) {
            throw new RiskEngineException(ErrorCode.INVALID_PRICE,
                    "行情价格必须为正数，symbol=" + symbol + "，price=" + price);
        }
        while (true) {
            MarketSnapshot cur = ref.get();
            Map<String, Quote> next = new HashMap<>(cur.quotes());
            next.put(symbol, new Quote(symbol, price, System.currentTimeMillis()));
            MarketSnapshot updated = new MarketSnapshot(cur.version() + 1,
                    System.currentTimeMillis(), Map.copyOf(next));
            if (ref.compareAndSet(cur, updated)) {
                return updated;
            }
            // CAS 失败说明并发写入，重试基于最新快照再合并
        }
    }
}
