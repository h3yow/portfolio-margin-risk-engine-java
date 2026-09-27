package com.github.h3yow.portfoliomarginriskenginejava.risk;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.Position;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskReport;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketDataProvider;
import com.github.h3yow.portfoliomarginriskenginejava.market.Quote;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 纯函数式风险计算器：输入是确定版本的 {@link AccountSnapshot}，输出 {@link RiskReport}。
 *
 * <p><b>风险模型（本地组合保证金，简化口径）</b></p>
 * <ul>
 *   <li>持仓市值 MV = Σ quantity × price（空头 quantity 为负，MV 为负）。</li>
 *   <li>权益 Equity = 现金 cash + MV。</li>
 *   <li>维持保证金 MM = Σ |quantity| × price × 维持保证金率 m（多空均计提）。</li>
 *   <li>可用保证金 Available = Equity − MM；为负表示已无可用保证金。</li>
 *   <li>强平判定：Equity &lt; MM 即触发（相等时不触发，处于临界）。</li>
 * </ul>
 *
 * <p><b>不可定义情形</b>（不静默按零处理，报告 {@code defined=false} 且数值字段全为 null，
 * reasons 给出可区分原因）：行情缺失 {@link RiskErrorCode#MARKET_DATA_MISSING}、
 * 价格非正 {@link RiskErrorCode#INVALID_PRICE}。</p>
 */
public final class RiskCalculator {

    private final MarketDataProvider marketData;
    private final BigDecimal maintenanceMarginRate;

    public RiskCalculator(MarketDataProvider marketData, BigDecimal maintenanceMarginRate) {
        if (maintenanceMarginRate == null
                || maintenanceMarginRate.signum() <= 0
                || maintenanceMarginRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "维持保证金率必须位于 (0,1]，实际: " + maintenanceMarginRate);
        }
        this.marketData = marketData;
        this.maintenanceMarginRate = maintenanceMarginRate;
    }

    public RiskReport evaluate(AccountSnapshot snapshot) {
        long now = System.currentTimeMillis();
        Set<RiskErrorCode> reasons = new LinkedHashSet<>();
        List<String> basisLines = new ArrayList<>();

        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal maintenanceMargin = BigDecimal.ZERO;
        boolean defined = true;

        basisLines.add("cash=" + snapshot.cash().toPlainString());
        basisLines.add("positions=" + snapshot.positions().size());

        for (Position p : snapshot.positions()) {
            Optional<Quote> quoteOpt = marketData.quote(p.symbol());
            if (quoteOpt.isEmpty()) {
                defined = false;
                reasons.add(RiskErrorCode.MARKET_DATA_MISSING);
                basisLines.add("symbol=" + p.symbol() + " qty=" + p.quantity().toPlainString()
                        + " -> 行情缺失，指标不可定义");
                continue;
            }
            Quote quote = quoteOpt.get();
            if (quote.price() == null || quote.price().signum() <= 0) {
                defined = false;
                reasons.add(RiskErrorCode.INVALID_PRICE);
                basisLines.add("symbol=" + p.symbol() + " qty=" + p.quantity().toPlainString()
                        + " price=" + quote.price() + " -> 价格非正，指标不可定义");
                continue;
            }

            BigDecimal mv = p.quantity().multiply(quote.price());
            BigDecimal notionalAbs = p.quantity().abs().multiply(quote.price());
            BigDecimal mmPart = notionalAbs.multiply(maintenanceMarginRate);
            marketValue = marketValue.add(mv);
            maintenanceMargin = maintenanceMargin.add(mmPart);
            basisLines.add("symbol=" + p.symbol()
                    + " qty=" + p.quantity().toPlainString()
                    + " price=" + quote.price().toPlainString()
                    + " mv=" + mv.toPlainString()
                    + " mmPart=" + mmPart.toPlainString());
        }

        if (!defined) {
            basisLines.add("结论: defined=false，权益/维持保证金/可用保证金均不可定义，"
                    + "不进行强平判定");
            return new RiskReport(snapshot.accountId(), snapshot.version(), now,
                    false, List.copyOf(reasons), null, null, null, false,
                    String.join("; ", basisLines));
        }

        BigDecimal equity = snapshot.cash().add(marketValue);
        BigDecimal availableMargin = equity.subtract(maintenanceMargin);
        boolean liquidation = equity.compareTo(maintenanceMargin) < 0;

        basisLines.add("marketValue=" + marketValue.toPlainString());
        basisLines.add("equity=cash+marketValue=" + equity.toPlainString());
        basisLines.add("maintenanceMargin=Σ|qty|*price*"
                + maintenanceMarginRate.toPlainString() + "="
                + maintenanceMargin.toPlainString());
        basisLines.add("availableMargin=equity-maintenanceMargin="
                + availableMargin.toPlainString());
        basisLines.add("强平判定 equity(" + equity.toPlainString() + ") "
                + (liquidation ? "<" : ">=")
                + " maintenanceMargin(" + maintenanceMargin.toPlainString() + ") -> "
                + (liquidation ? "TRIGGER" : "SAFE"));

        return new RiskReport(snapshot.accountId(), snapshot.version(), now,
                true, List.of(), equity, maintenanceMargin, availableMargin,
                liquidation, String.join("; ", basisLines));
    }
}
