package com.github.h3yow.portfoliomarginriskenginejava.tests;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.Position;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskDecision;
import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.market.Quote;
import com.github.h3yow.portfoliomarginriskenginejava.risk.MarginPolicy;
import com.github.h3yow.portfoliomarginriskenginejava.risk.RiskCalculator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险指标边界与错误分类单测。每个用例打印输入、状态版本与判定依据。
 *
 * <p>风险模型（保证金账户，允许保证金贷款）：cash 可为负，负值代表对券商的欠款；
 * equity = cash + 持仓市值；MM = Σ 市值 * marginRate；可用保证金 = equity - MM；
 * availableMargin &lt; 0 触发强平。</p>
 */
class RiskCalculatorTest {

    private static final Logger log = LoggerFactory.getLogger(RiskCalculatorTest.class);

    private final MarginPolicy policy = new MarginPolicy();
    private final RiskCalculator calculator = new RiskCalculator(policy);

    private AccountSnapshot account(long version, String cash, Position... positions) {
        Map<String, Position> map = new HashMap<>();
        for (Position p : positions) {
            map.put(p.symbol(), p);
        }
        return new AccountSnapshot("acc", version, System.currentTimeMillis(),
                new BigDecimal(cash), map);
    }

    private MarketSnapshot market(long version, Quote... quotes) {
        Map<String, Quote> map = new HashMap<>();
        for (Quote q : quotes) {
            map.put(q.symbol(), q);
        }
        return new MarketSnapshot(version, System.currentTimeMillis(), map);
    }

    @Test
    void equityAndMarginCalculationOk() {
        policy.setRate("AAA", new BigDecimal("0.2"));
        // 现金 1000 + 100@10 = 权益 2000；MM = 1000*0.2 = 200；可用 1800
        AccountSnapshot acc = account(3, "1000",
                new Position("AAA", new BigDecimal("100"), new BigDecimal("8")));
        MarketSnapshot mkt = market(7, new Quote("AAA", new BigDecimal("10"), 0L));
        log.info("[用例-正常] 输入 accountVersion={} quoteVersion={} cash=1000 position=AAA:100@8 price=10 rate=0.2",
                acc.version(), mkt.version());

        RiskDecision d = calculator.evaluate(acc, mkt);
        d.reasons().forEach(r -> log.info("[用例-正常] 判定依据 v={} | {}", d.accountVersion(), r));

        assertEquals(3, d.accountVersion());
        assertEquals(7, d.quoteVersion());
        assertEquals(new BigDecimal("2000"), d.metrics().equity());
        assertEquals(new BigDecimal("200.0"), d.metrics().maintenanceMargin());
        assertEquals(new BigDecimal("1800.0"), d.metrics().availableMargin());
        assertFalse(d.liquidation());
        assertEquals(RiskDecision.DecisionStatus.OK, d.status());
    }

    @Test
    void availableMarginExactlyZeroIsNotLiquidation() {
        // 边界：cash=0（自有资金恰好用完），持仓 100@1，rate=1.0
        // 权益 100，MM 100，可用 0 —— 等于 0 不触发（规则为严格小于 0）
        policy.setRate("AAA", new BigDecimal("1.0"));
        AccountSnapshot acc = account(5, "0",
                new Position("AAA", new BigDecimal("100"), new BigDecimal("1")));
        MarketSnapshot mkt = market(2, new Quote("AAA", new BigDecimal("1"), 0L));
        log.info("[用例-边界=0] 输入 accountVersion={} quoteVersion={} cash=0 持仓=AAA:100@1 price=1 rate=1.0",
                acc.version(), mkt.version());

        RiskDecision d = calculator.evaluate(acc, mkt);
        d.reasons().forEach(r -> log.info("[用例-边界=0] 判定依据 v={} | {}", d.accountVersion(), r));
        assertEquals(0, d.metrics().availableMargin().signum(), "可用保证金边界应为 0");
        assertFalse(d.liquidation(), "可用保证金等于 0 不属于强平触发条件");
    }

    @Test
    void liquidationTriggeredWhenEquityBelowMaintenanceMargin() {
        // 杠杆买入：自有资金 200 + 保证金贷款 800，买入 100@10 => cash=-800
        // 维持保证金率 0.5。价格跌到 7：权益 = -800 + 700 = -100，
        // MM = 700*0.5 = 350，可用 = -100 - 350 = -450 < 0 => 触发强平
        policy.setRate("AAA", new BigDecimal("0.5"));
        AccountSnapshot acc = account(9, "-800",
                new Position("AAA", new BigDecimal("100"), new BigDecimal("10")));
        MarketSnapshot mkt = market(4, new Quote("AAA", new BigDecimal("7"), 0L));
        log.info("[用例-强平] 输入 accountVersion={} quoteVersion={} cash=-800 持仓=AAA:100@10 price=7 rate=0.5",
                acc.version(), mkt.version());

        RiskDecision d = calculator.evaluate(acc, mkt);
        d.reasons().forEach(r -> log.info("[用例-强平] 判定依据 v={} | {}", d.accountVersion(), r));

        assertEquals(new BigDecimal("-100"), d.metrics().equity());
        assertEquals(new BigDecimal("350.0"), d.metrics().maintenanceMargin());
        assertEquals(new BigDecimal("-450.0"), d.metrics().availableMargin());
        assertTrue(d.liquidation());
        assertEquals(RiskDecision.DecisionStatus.LIQUIDATION, d.status());
    }

    @Test
    void leveragedPositionAboveMarginIsOk() {
        // 同一杠杆持仓，价格维持 10：权益 = -800 + 1000 = 200，
        // MM = 500，可用 = -300 < 0 => 买入价本身就低于维持要求（极端高杠杆），
        // 说明初始保证金率必须 > 维持保证金率。改取 rate=0.2：MM=200，可用=0（边界）。
        policy.setRate("AAA", new BigDecimal("0.2"));
        AccountSnapshot acc = account(11, "-800",
                new Position("AAA", new BigDecimal("100"), new BigDecimal("10")));
        MarketSnapshot mkt = market(5, new Quote("AAA", new BigDecimal("10"), 0L));
        log.info("[用例-杠杆正常] 输入 accountVersion={} quoteVersion={} cash=-800 price=10 rate=0.2",
                acc.version(), mkt.version());

        RiskDecision d = calculator.evaluate(acc, mkt);
        d.reasons().forEach(r -> log.info("[用例-杠杆正常] 判定依据 v={} | {}", d.accountVersion(), r));
        assertEquals(new BigDecimal("200"), d.metrics().equity());
        assertEquals(0, d.metrics().availableMargin().signum());
        assertFalse(d.liquidation());
    }

    @Test
    void missingQuoteIsExplicitErrorNotZero() {
        policy.setRate("AAA", new BigDecimal("0.2"));
        AccountSnapshot acc = account(1, "0",
                new Position("AAA", new BigDecimal("10"), new BigDecimal("10")));
        MarketSnapshot mkt = market(1);
        log.info("[用例-缺行情] 输入 accountVersion={} quoteVersion={} 持仓=AAA:10 行情=空",
                acc.version(), mkt.version());

        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> calculator.evaluate(acc, mkt));
        assertEquals(ErrorCode.MARKET_DATA_MISSING, ex.getCode());
        log.info("[用例-缺行情] 按预期拒绝 code={} message={}", ex.getCode(), ex.getMessage());
    }

    @Test
    void nonPositivePriceIsExplicitError() {
        policy.setRate("AAA", new BigDecimal("0.2"));
        AccountSnapshot acc = account(2, "0",
                new Position("AAA", new BigDecimal("10"), new BigDecimal("10")));
        MarketSnapshot mkt = market(3, new Quote("AAA", BigDecimal.ZERO, 0L));
        log.info("[用例-非法价] 输入 accountVersion={} quoteVersion={} price=0",
                acc.version(), mkt.version());

        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> calculator.evaluate(acc, mkt));
        assertEquals(ErrorCode.INVALID_PRICE, ex.getCode());
        log.info("[用例-非法价] 按预期拒绝 code={} message={}", ex.getCode(), ex.getMessage());
    }

    @Test
    void missingMarginRateIsExplicitError() {
        AccountSnapshot acc = account(4, "0",
                new Position("AAA", new BigDecimal("10"), new BigDecimal("10")));
        MarketSnapshot mkt = market(6, new Quote("AAA", new BigDecimal("10"), 0L));
        log.info("[用例-缺保证金率] 输入 accountVersion={} quoteVersion={} rate=未配置",
                acc.version(), mkt.version());

        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> calculator.evaluate(acc, mkt));
        assertEquals(ErrorCode.MARGIN_RATE_MISSING, ex.getCode());
        log.info("[用例-缺保证金率] 按预期拒绝 code={} message={}", ex.getCode(), ex.getMessage());
    }

    @Test
    void emptyAccountHasWellDefinedZeroMetrics() {
        AccountSnapshot acc = account(8, "500");
        MarketSnapshot mkt = market(10);
        log.info("[用例-空仓] 输入 accountVersion={} quoteVersion={} cash=500 无持仓",
                acc.version(), mkt.version());

        RiskDecision d = calculator.evaluate(acc, mkt);
        d.reasons().forEach(r -> log.info("[用例-空仓] 判定依据 v={} | {}", d.accountVersion(), r));
        assertEquals(new BigDecimal("500"), d.metrics().equity());
        assertEquals(0, d.metrics().maintenanceMargin().signum());
        assertEquals(new BigDecimal("500"), d.metrics().availableMargin());
    }
}
