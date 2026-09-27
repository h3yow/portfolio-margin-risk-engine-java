package com.github.h3yow.portfoliomarginriskenginejava.service;

import com.github.h3yow.portfoliomarginriskenginejava.account.AccountRepository;
import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.OperationResult;
import com.github.h3yow.portfoliomarginriskenginejava.domain.Position;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskDecision;
import com.github.h3yow.portfoliomarginriskenginejava.domain.TradeSide;
import com.github.h3yow.portfoliomarginriskenginejava.domain.TransferType;
import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketDataService;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.risk.MarginPolicy;
import com.github.h3yow.portfoliomarginriskenginejava.risk.RiskCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用服务：账户资金/持仓变更的幂等入口，以及基于确定版本快照的风险查询。
 *
 * <p>关键保证：</p>
 * <ul>
 *   <li><b>幂等</b>：以 (accountId, requestId) 为业务请求的唯一键，首次提交经 CAS 落地；
 *       重复提交（含并发重复）只返回首次结论 {@code duplicate=true}，绝不重复生效。</li>
 *   <li><b>一致</b>：每次变更/计算都读取某个完整不可变快照，结论可追溯到账户版本与行情版本。</li>
 *   <li><b>守恒</b>：划转只改现金、交易按成交价等额转移现金与持仓数量，
 *       超额操作（余额/持仓不足、版本不匹配）在状态迁移内被整体拒绝，不产生部分生效。</li>
 *   <li><b>杠杆</b>：保证金账户允许杠杆买入，现金可为负（保证金贷款）；
 *       开仓须通过初始保证金校验（自有权益足以覆盖新增持仓的维持保证金）。</li>
 * </ul>
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final AccountRepository repository;
    private final MarketDataService marketDataService;
    private final RiskCalculator riskCalculator;
    private final MarginPolicy marginPolicy;

    /**
     * 幂等键 -> 首次处理结果。value 为 CompletableFuture：
     * 并发重复提交时后到者等待首个请求的结论，二者都不会造成双重生效。
     */
    private final ConcurrentHashMap<String, CompletableFuture<OperationResult>> idempotency =
            new ConcurrentHashMap<>();

    public PortfolioService(AccountRepository repository,
                            MarketDataService marketDataService,
                            RiskCalculator riskCalculator,
                            MarginPolicy marginPolicy) {
        this.repository = repository;
        this.marketDataService = marketDataService;
        this.riskCalculator = riskCalculator;
        this.marginPolicy = marginPolicy;
    }

    public AccountSnapshot createAccount(String accountId) {
        AccountSnapshot s = repository.create(accountId);
        log.info("开户成功 accountId={} version={}", accountId, s.version());
        return s;
    }

    public AccountSnapshot getSnapshot(String accountId) {
        return repository.get(accountId);
    }

    /**
     * 资金划转：DEPOSIT 入金 / WITHDRAW 出金。
     * 出金不得使用保证金贷款（仅可提取自有正现金），超额以 INSUFFICIENT_FUNDS 拒绝；
     * 是否允许在有负债时动用占用中的现金由保守策略统一禁止（cash - amount 不得为负）。
     */
    public OperationResult transfer(String accountId,
                                    String requestId,
                                    TransferType type,
                                    BigDecimal amount,
                                    Long expectedVersion) {
        if (type == null) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "划转类型不能为空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT,
                    "划转金额必须为正数，amount=" + amount);
        }
        log.info("收到资金划转请求 accountId={} requestId={} type={} amount={} expectedVersion={}",
                accountId, requestId, type, amount, expectedVersion);

        return applyIdempotent(accountId, requestId, current -> {
            checkExpectedVersion(current, expectedVersion);
            BigDecimal newCash;
            String action;
            if (type == TransferType.DEPOSIT) {
                newCash = current.cash().add(amount);
                action = "入金";
            } else {
                if (current.cash().compareTo(amount) < 0) {
                    throw new RiskEngineException(ErrorCode.INSUFFICIENT_FUNDS, String.format(
                            "出金失败：余额不足，accountId=%s，requestId=%s，当前现金=%s，请求金额=%s，version=%d",
                            accountId, requestId, current.cash().toPlainString(),
                            amount.toPlainString(), current.version()));
                }
                newCash = current.cash().subtract(amount);
                action = "出金";
            }
            AccountSnapshot next = new AccountSnapshot(accountId, current.version() + 1,
                    System.currentTimeMillis(), newCash, current.positions());
            return new Transition(next, String.format(
                    "%s成功 %s，现金 %s -> %s", action, amount.toPlainString(),
                    current.cash().toPlainString(), newCash.toPlainString()));
        });
    }

    /**
     * 持仓调整（成交）：
     * BUY 按成交价支付现金、增加持仓（加权平均成本）；现金不足部分形成保证金贷款（现金为负），
     * 但必须通过初始保证金校验：成交后账户权益（以成本口径估算）足以覆盖全部维持保证金；
     * SELL 按成交价收进现金、减少持仓。保证金/持仓任一不足即整体拒绝。
     */
    public OperationResult adjustPosition(String accountId,
                                          String requestId,
                                          String symbol,
                                          TradeSide side,
                                          BigDecimal qty,
                                          BigDecimal price,
                                          Long expectedVersion) {
        if (symbol == null || symbol.isBlank()) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "symbol 不能为空");
        }
        if (side == null) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "交易方向不能为空");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT,
                    "成交数量必须为正数，qty=" + qty);
        }
        if (price == null || price.signum() <= 0) {
            throw new RiskEngineException(ErrorCode.INVALID_PRICE,
                    "成交价格必须为正数，symbol=" + symbol + "，price=" + price);
        }
        log.info("收到持仓调整请求 accountId={} requestId={} symbol={} side={} qty={} price={} expectedVersion={}",
                accountId, requestId, symbol, side, qty, price, expectedVersion);

        return applyIdempotent(accountId, requestId, current -> {
            checkExpectedVersion(current, expectedVersion);
            BigDecimal consideration = qty.multiply(price);
            Map<String, Position> nextPositions = new HashMap<>(current.positions());
            BigDecimal newCash;

            if (side == TradeSide.BUY) {
                Position old = current.position(symbol);
                BigDecimal rate = marginPolicy.requireRate(symbol);
                BigDecimal newQty = (old == null ? BigDecimal.ZERO : old.qty()).add(qty);
                BigDecimal newPositionCost = newQty.multiply(price);
                // 其它存量持仓按其成本口径估值（成交发生瞬间的保守、确定口径，不依赖行情快照）
                BigDecimal otherCost = BigDecimal.ZERO;
                BigDecimal otherMargin = BigDecimal.ZERO;
                for (Position p : current.positions().values()) {
                    if (p.symbol().equals(symbol)) {
                        continue;
                    }
                    BigDecimal c = p.qty().multiply(p.avgCost());
                    otherCost = otherCost.add(c);
                    otherMargin = otherMargin.add(c.multiply(marginPolicy.requireRate(p.symbol())));
                }
                // 成交后预估权益与维持保证金（成本口径）
                BigDecimal proFormaEquity = current.cash().subtract(consideration)
                        .add(newPositionCost).add(otherCost);
                BigDecimal proFormaMargin = newPositionCost.multiply(rate).add(otherMargin);
                BigDecimal proFormaAvailable = proFormaEquity.subtract(proFormaMargin);
                if (proFormaAvailable.signum() < 0) {
                    throw new RiskEngineException(ErrorCode.INSUFFICIENT_FUNDS, String.format(
                            "买入失败：不满足初始保证金要求，accountId=%s，requestId=%s，symbol=%s，"
                                    + "成交额=%s，成交后预估权益=%s，维持保证金=%s，可用=%s，version=%d",
                            accountId, requestId, symbol, consideration.toPlainString(),
                            proFormaEquity.toPlainString(), proFormaMargin.toPlainString(),
                            proFormaAvailable.toPlainString(), current.version()));
                }
                newCash = current.cash().subtract(consideration);
                if (old == null) {
                    nextPositions.put(symbol, new Position(symbol, qty, price));
                } else {
                    // 加权平均成本，固定 10 位小数 HALF_UP，避免除不尽时整体失败
                    BigDecimal newAvgCost = old.qty().multiply(old.avgCost())
                            .add(consideration)
                            .divide(newQty, 10, RoundingMode.HALF_UP);
                    nextPositions.put(symbol, new Position(symbol, newQty, newAvgCost));
                }
            } else {
                Position old = current.position(symbol);
                if (old == null || old.qty().compareTo(qty) < 0) {
                    BigDecimal held = old == null ? BigDecimal.ZERO : old.qty();
                    throw new RiskEngineException(ErrorCode.INSUFFICIENT_POSITION, String.format(
                            "卖出失败：持仓不足，accountId=%s，requestId=%s，symbol=%s，持有=%s，请求=%s，version=%d",
                            accountId, requestId, symbol, held.toPlainString(),
                            qty.toPlainString(), current.version()));
                }
                newCash = current.cash().add(consideration);
                BigDecimal remaining = old.qty().subtract(qty);
                if (remaining.signum() == 0) {
                    nextPositions.remove(symbol);
                } else {
                    nextPositions.put(symbol, new Position(symbol, remaining, old.avgCost()));
                }
            }

            AccountSnapshot next = new AccountSnapshot(accountId, current.version() + 1,
                    System.currentTimeMillis(), newCash, nextPositions);
            return new Transition(next, String.format(
                    "%s %s 成交 %s@%s，成交额 %s，现金 %s -> %s",
                    side, symbol, qty.toPlainString(), price.toPlainString(),
                    consideration.toPlainString(),
                    current.cash().toPlainString(), newCash.toPlainString()));
        });
    }

    /**
     * 风险查询：读取某确定版本的账户快照与行情快照后做纯函数计算。
     * 两个快照各自完整一致；结论同时记录两个版本号，保证可追溯。
     */
    public RiskDecision evaluateRisk(String accountId) {
        AccountSnapshot account = repository.get(accountId);
        MarketSnapshot market = marketDataService.snapshot();
        log.info("风险查询输入 accountId={} accountVersion={} quoteVersion={} cash={} positions={}",
                accountId, account.version(), market.version(),
                account.cash().toPlainString(), account.positions().keySet());
        RiskDecision decision = riskCalculator.evaluate(account, market);
        log.info("风险判定结论 accountId={} accountVersion={} quoteVersion={} status={} liquidation={} equity={} MM={} available={}",
                decision.accountId(), decision.accountVersion(), decision.quoteVersion(),
                decision.status(), decision.liquidation(),
                decision.metrics().equity().toPlainString(),
                decision.metrics().maintenanceMargin().toPlainString(),
                decision.metrics().availableMargin().toPlainString());
        decision.reasons().forEach(r -> log.info("判定依据 accountId={} version={} | {}",
                accountId, decision.accountVersion(), r));
        return decision;
    }

    // ------------------------------------------------------------------

    /** 单次状态迁移的产物：下一快照 + 结果描述。 */
    private record Transition(AccountSnapshot next, String message) {
    }

    /**
     * 幂等执行外壳。首个 requestId 占用请求键并执行 CAS 迁移；
     * 并发的重复提交等待并复用首次结论，不再次执行。
     *
     * <p>语义约定：只有<b>成功生效</b>的请求才永久占用幂等键（重复提交永不重复生效）；
     * 业务失败（余额不足、版本冲突等）时移除占用，允许调用方修正后用同一 requestId 重试。
     * 失败的首个请求本就没有改变任何状态，因此重试不存在双重生效问题。</p>
     */
    private OperationResult applyIdempotent(String accountId,
                                            String requestId,
                                            java.util.function.Function<AccountSnapshot, Transition> transition) {
        if (requestId == null || requestId.isBlank()) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "requestId 不能为空");
        }
        // 先确认账户存在，错误与幂等无关，直接抛出
        repository.get(accountId);

        String key = accountId + "|" + requestId;
        CompletableFuture<OperationResult> owned = new CompletableFuture<>();
        CompletableFuture<OperationResult> existing = idempotency.putIfAbsent(key, owned);
        if (existing != null) {
            // 重复提交：等待首个请求落定。成功则复用其结论；首个失败则由首个调用方移除占用，
            // 这里直接得到其失败异常，调用方可重新提交。
            try {
                OperationResult first = existing.join();
                OperationResult dup = new OperationResult(requestId, false, true,
                        first.accountVersion(),
                        "重复请求，未重复生效；复用 requestId=" + requestId
                                + " 首次结果（version=" + first.accountVersion() + "）：" + first.message());
                log.info("重复提交被识别 accountId={} requestId={} firstVersion={}",
                        accountId, requestId, first.accountVersion());
                return dup;
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
        }

        try {
            final OperationResult[] out = new OperationResult[1];
            AccountSnapshot applied = repository.mutate(accountId, current -> {
                Transition t = transition.apply(current);
                out[0] = new OperationResult(requestId, true, false,
                        t.next().version(), t.message());
                return t.next();
            });
            log.info("业务请求生效 accountId={} requestId={} version {} -> {}",
                    accountId, requestId, applied.version() - 1, applied.version());
            owned.complete(out[0]);
            return out[0];
        } catch (Throwable t) {
            // 首次请求失败：释放幂等占用，允许同一 requestId 在修正后重试
            owned.completeExceptionally(t);
            idempotency.remove(key, owned);
            throw t;
        }
    }

    private static void checkExpectedVersion(AccountSnapshot current, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion.longValue() != current.version()) {
            throw new RiskEngineException(ErrorCode.VERSION_CONFLICT, String.format(
                    "版本冲突：请求期望 version=%d，但账户当前 version=%d，请基于最新状态重试",
                    expectedVersion, current.version()));
        }
    }
}
