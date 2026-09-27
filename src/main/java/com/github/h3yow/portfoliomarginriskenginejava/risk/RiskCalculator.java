package com.github.h3yow.portfoliomarginriskenginejava.risk;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.Position;
import com.github.h3yow.portfoliomarginriskenginejava.domain.PositionValuation;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskDecision;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskMetrics;
import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.market.Quote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯函数式风险计算。
 *
 * <p>给定确定版本的账户快照与行情快照，产出 {@link RiskDecision}：</p>
 * <ul>
 *   <li>权益 equity = cash + Σ(qty * price)（多头模型）；</li>
 *   <li>维持保证金 MM = Σ(qty * price * marginRate)；</li>
 *   <li>可用保证金 = equity - MM；</li>
 *   <li>当可用保证金 &lt; 0（权益不足以覆盖维持保证金）时触发强平判定。</li>
 * </ul>
 *
 * <p>行情缺失、价格非正、保证金率未配置均为显式错误，绝不按零处理；
 * 只要任一输入无法定义，整个指标即不可定义，直接失败而不是给出部分结论。</p>
 */
@Component
public class RiskCalculator {

    private final MarginPolicy marginPolicy;

    public RiskCalculator(MarginPolicy marginPolicy) {
        this.marginPolicy = marginPolicy;
    }

    public RiskDecision evaluate(AccountSnapshot account, MarketSnapshot market) {
        if (account == null) {
            throw new RiskEngineException(ErrorCode.ACCOUNT_NOT_FOUND, "账户快照为空");
        }
        if (market == null) {
            throw new RiskEngineException(ErrorCode.MARKET_DATA_MISSING, "行情快照为空");
        }

        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal maintenanceMargin = BigDecimal.ZERO;
        List<PositionValuation> valuations = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (Position p : account.positions().values()) {
            Quote quote = market.quotes().get(p.symbol());
            if (quote == null) {
                // 指标无法定义：显式失败，不静默按 0 估值
                throw new RiskEngineException(ErrorCode.MARKET_DATA_MISSING,
                        "缺少标的行情，无法计算风险指标，symbol=" + p.symbol()
                                + "，accountVersion=" + account.version()
                                + "，quoteVersion=" + market.version());
            }
            BigDecimal price = quote.price();
            if (price == null || price.signum() <= 0) {
                throw new RiskEngineException(ErrorCode.INVALID_PRICE,
                        "行情价格非正，无法计算风险指标，symbol=" + p.symbol()
                                + "，price=" + price);
            }
            BigDecimal rate = marginPolicy.requireRate(p.symbol());

            BigDecimal mv = p.qty().multiply(price);
            BigDecimal mm = mv.multiply(rate);
            marketValue = marketValue.add(mv);
            maintenanceMargin = maintenanceMargin.add(mm);

            valuations.add(new PositionValuation(p.symbol(), p.qty(), price, mv, rate, mm));
            reasons.add(String.format(
                    "标的 %s：数量 %s，价格 %s（行情版本 %d），市值 %s，维持保证金率 %s，维持保证金 %s",
                    p.symbol(), p.qty().toPlainString(), price.toPlainString(),
                    market.version(), mv.toPlainString(),
                    rate.toPlainString(), mm.toPlainString()));
        }

        BigDecimal equity = account.cash().add(marketValue);
        BigDecimal availableMargin = equity.subtract(maintenanceMargin);

        RiskMetrics metrics = new RiskMetrics(account.version(), market.version(),
                equity, maintenanceMargin, availableMargin, List.copyOf(valuations));

        boolean liquidation = availableMargin.signum() < 0;
        reasons.add(0, String.format(
                "现金 %s + 持仓总市值 %s = 权益 %s；维持保证金合计 %s；可用保证金 = 权益 - 维持保证金 = %s",
                account.cash().toPlainString(), marketValue.toPlainString(),
                equity.toPlainString(), maintenanceMargin.toPlainString(),
                availableMargin.toPlainString()));
        reasons.add(liquidation
                ? String.format("可用保证金 %s < 0，权益不足以覆盖维持保证金，触发强平判定",
                        availableMargin.toPlainString())
                : String.format("可用保证金 %s >= 0，权益足以覆盖维持保证金，不触发强平",
                        availableMargin.toPlainString()));

        return new RiskDecision(account.accountId(), account.version(), market.version(),
                liquidation ? RiskDecision.DecisionStatus.LIQUIDATION : RiskDecision.DecisionStatus.OK,
                liquidation, List.copyOf(reasons), metrics, System.currentTimeMillis());
    }
}
