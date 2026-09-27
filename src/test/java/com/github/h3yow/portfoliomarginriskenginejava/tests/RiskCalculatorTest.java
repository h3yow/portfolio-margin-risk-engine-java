package com.github.h3yow.portfoliomarginriskenginejava.tests;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.Position;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskReport;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketDataProvider;
import com.github.h3yow.portfoliomarginriskenginejava.market.Quote;
import com.github.h3yow.portfoliomarginriskenginejava.risk.RiskCalculator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指标边界与不可定义情形单测；日志打印输入、状态版本与判定依据。 */
class RiskCalculatorTest {

    private static final Logger log = LoggerFactory.getLogger(RiskCalculatorTest.class);
    private static final BigDecimal MM_RATE = new BigDecimal("0.25");

    private MarketDataProvider market(Map<String, String> prices) {
        Map<String, Quote> map = new ConcurrentHashMap<>();
        prices.forEach((s, p) -> map.put(s, new Quote(s, new BigDecimal(p), 1L)));
        return symbol -> Optional.ofNullable(map.get(symbol));
    }

    private RiskReport run(String caseName, AccountSnapshot snap, MarketDataProvider md) {
        RiskReport r = new RiskCalculator(md, MM_RATE).evaluate(snap);
        log.info("[{}] 输入: accountId={} version={} cash={} positions={}",
                caseName, snap.accountId(), snap.version(), snap.cash(), snap.positions());
        log.info("[{}] 状态版本={} defined={} equity={} mm={} available={} liquidation={} reasons={}",
                caseName, r.version(), r.defined(), r.equity(), r.maintenanceMargin(),
                r.availableMargin(), r.liquidation(), r.reasons());
        log.info("[{}] 判定依据: {}", caseName, r.basis());
        return r;
    }

    @Test
    void safeAccountWhenEquityAboveMaintenanceMargin() {
        // cash=1000, long 10 @100 -> mv=1000, equity=2000, mm=250, available=1750
        AccountSnapshot snap = new AccountSnapshot("a1", 7L, new BigDecimal("1000"),
                List.of(new Position("AAA", new BigDecimal("10"))));
        RiskReport r = run("SAFE", snap, market(Map.of("AAA", "100")));
        assertTrue(r.defined());
        assertEquals(7L, r.version());
        assertEquals(new BigDecimal("2000"), r.equity());
        assertEquals(new BigDecimal("250.00"), r.maintenanceMargin());
        assertEquals(new BigDecimal("1750.00"), r.availableMargin());
        assertFalse(r.liquidation());
    }

    @Test
    void boundaryEquityEqualsMaintenanceMarginIsNotLiquidation() {
        // cash=250, long 10 @100 -> equity=1250, mm=250 -> available=1000? adjust:
        // 构造临界: cash=0, short? 简单构造: equity==mm
        // cash=-750, long 10 @100 -> equity=250 == mm=250 -> 不触发
        AccountSnapshot snap = new AccountSnapshot("a2", 3L, new BigDecimal("-750"),
                List.of(new Position("AAA", new BigDecimal("10"))));
        RiskReport r = run("BOUNDARY", snap, market(Map.of("AAA", "100")));
        assertTrue(r.defined());
        assertEquals(0, r.equity().compareTo(r.maintenanceMargin()));
        assertEquals(0, r.availableMargin().compareTo(BigDecimal.ZERO));
        assertFalse(r.liquidation(), "权益等于维持保证金时不得触发强平");
    }

    @Test
    void liquidationWhenEquityBelowMaintenanceMargin() {
        // cash=-800, long 10 @100 -> equity=200 < mm=250 -> 触发
        AccountSnapshot snap = new AccountSnapshot("a3", 4L, new BigDecimal("-800"),
                List.of(new Position("AAA", new BigDecimal("10"))));
        RiskReport r = run("LIQUIDATION", snap, market(Map.of("AAA", "100")));
        assertTrue(r.defined());
        assertTrue(r.liquidation());
        assertTrue(r.availableMargin().signum() < 0);
    }

    @Test
    void missingMarketDataIsUndefinedNotZero() {
        AccountSnapshot snap = new AccountSnapshot("a4", 5L, new BigDecimal("1000"),
                List.of(new Position("MISSING", new BigDecimal("3"))));
        RiskReport r = run("MISSING_QUOTE", snap, market(Map.of("OTHER", "100")));
        assertFalse(r.defined());
        assertTrue(r.reasons().contains(RiskErrorCode.MARKET_DATA_MISSING));
        assertNull(r.equity());
        assertNull(r.maintenanceMargin());
        assertNull(r.availableMargin());
        assertFalse(r.liquidation());
    }

    @Test
    void invalidPriceIsUndefinedWithDistinctReason() {
        AccountSnapshot snap = new AccountSnapshot("a5", 6L, new BigDecimal("1000"),
                List.of(new Position("BAD", new BigDecimal("3")),
                        new Position("GOOD", new BigDecimal("2"))));
        RiskReport r = run("INVALID_PRICE", snap, market(Map.of("BAD", "0", "GOOD", "50")));
        assertFalse(r.defined());
        assertTrue(r.reasons().contains(RiskErrorCode.INVALID_PRICE));
        assertNull(r.equity());
    }

    @Test
    void emptyAccountIsDefinedWithZeroMetrics() {
        // 没有持仓 => 不需要任何行情，指标天然有定义
        AccountSnapshot snap = new AccountSnapshot("a6", 1L, new BigDecimal("500"), List.of());
        RiskReport r = run("EMPTY", snap, market(Map.of()));
        assertTrue(r.defined());
        assertEquals(new BigDecimal("500"), r.equity());
        assertEquals(0, r.maintenanceMargin().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("500"), r.availableMargin());
        assertFalse(r.liquidation());
    }

    @Test
    void shortPositionContributesNegativeMarketValueAndMargin() {
        // cash=2000, short 10 @100 -> mv=-1000, equity=1000, mm=250, available=750
        AccountSnapshot snap = new AccountSnapshot("a7", 2L, new BigDecimal("2000"),
                List.of(new Position("AAA", new BigDecimal("-10"))));
        RiskReport r = run("SHORT", snap, market(Map.of("AAA", "100")));
        assertTrue(r.defined());
        assertEquals(new BigDecimal("1000"), r.equity());
        assertEquals(new BigDecimal("250.00"), r.maintenanceMargin());
        assertFalse(r.liquidation());
    }
}
