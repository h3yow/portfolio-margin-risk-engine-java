package com.github.h3yow.portfoliomarginriskenginejava.tests;

import com.github.h3yow.portfoliomarginriskenginejava.account.AccountRepository;
import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.OperationResult;
import com.github.h3yow.portfoliomarginriskenginejava.domain.Position;
import com.github.h3yow.portfoliomarginriskenginejava.domain.TradeSide;
import com.github.h3yow.portfoliomarginriskenginejava.domain.TransferType;
import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketDataService;
import com.github.h3yow.portfoliomarginriskenginejava.risk.MarginPolicy;
import com.github.h3yow.portfoliomarginriskenginejava.risk.RiskCalculator;
import com.github.h3yow.portfoliomarginriskenginejava.service.PortfolioService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发更新一致性、资金/持仓守恒、重复提交与冲突拒绝测试。
 *
 * <p>压力结束后验证守恒恒等式：</p>
 * <pre>
 *   期末现金 = 初始现金 + Σ(入金) - Σ(出金) - Σ(买入成交额) + Σ(卖出成交额)
 *   期末某标的持仓数量 = Σ(买入量) - Σ(卖出量)
 * </pre>
 * 压力期间每个线程只交易自己专属的标的，使所有请求都合法成功，
 * 从而把“并发正确性”与“业务拒绝路径”分开验证。
 */
class AccountConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(AccountConsistencyTest.class);

    private PortfolioService newService() {
        AccountRepository repo = new AccountRepository();
        MarketDataService market = new MarketDataService();
        MarginPolicy policy = new MarginPolicy();
        RiskCalculator calculator = new RiskCalculator(policy);
        return new PortfolioService(repo, market, calculator, policy);
    }

    private static final int THREADS = 8;
    private static final int ROUNDS = 200;

    @Test
    void concurrentTransfersAndTradesConserveTotals() throws Exception {
        PortfolioService svc = newService();
        String acc = "ACC-CONC";
        svc.createAccount(acc);
        // 初始入金
        svc.transfer(acc, "req-init", TransferType.DEPOSIT, new BigDecimal("1000000"), null);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicLong netDeposits = new AtomicLong();
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                for (int i = 0; i < ROUNDS; i++) {
                    long amount = 1 + (tid * 31L + i * 7L) % 100;
                    String id = "t" + tid + "-i" + i;
                    try {
                        // 偶数轮入金、奇数轮出金；出金额远小于初始资金，预期全部成功
                        OperationResult r = svc.transfer(acc, id,
                                (i % 2 == 0) ? TransferType.DEPOSIT : TransferType.WITHDRAW,
                                BigDecimal.valueOf(amount), null);
                        if (r.applied()) {
                            applied.incrementAndGet();
                            netDeposits.addAndGet((i % 2 == 0) ? amount : -amount);
                        }
                    } catch (RiskEngineException e) {
                        rejected.incrementAndGet();
                        log.warn("划转被拒绝 requestId={} code={} msg={}",
                                id, e.getCode(), e.getMessage());
                    }
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        AccountSnapshot snap = svc.getSnapshot(acc);
        long expectedCash = 1_000_000L + netDeposits.get();
        log.info("[并发划转压力] 结束 accountVersion={} applied={} rejected={} netDeposits={} 期望现金={} 实际现金={}",
                snap.version(), applied.get(), rejected.get(), netDeposits.get(),
                expectedCash, snap.cash().toPlainString());

        assertEquals(0, rejected.get(), "充足余额下不应有划转被拒绝");
        assertEquals(THREADS * ROUNDS, applied.get(), "所有唯一请求都应恰好生效一次");
        assertEquals(THREADS * ROUNDS + 1, snap.version(),
                "版本号应等于成功变更次数（含初始入金）");
        assertEquals(BigDecimal.valueOf(expectedCash), snap.cash(),
                "资金守恒：期末现金必须等于初始现金加净划转");
        assertEquals(0, snap.positions().size(), "无交易时持仓必须为空");
    }

    @Test
    void concurrentDuplicateSubmissionAppliesExactlyOnce() throws Exception {
        PortfolioService svc = newService();
        String acc = "ACC-DUP";
        svc.createAccount(acc);

        int n = 16;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger appliedCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int t = 0; t < n; t++) {
            pool.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    // 所有线程同一时刻提交完全相同的业务请求（相同 requestId）
                    OperationResult r = svc.transfer(acc, "same-request-id",
                            TransferType.DEPOSIT, new BigDecimal("500"), null);
                    if (r.duplicate()) {
                        duplicateCount.incrementAndGet();
                    }
                    if (r.applied()) {
                        appliedCount.incrementAndGet();
                    }
                } catch (RiskEngineException e) {
                    if (e.getCode() == ErrorCode.VERSION_CONFLICT) {
                        conflictCount.incrementAndGet();
                    }
                    throw e;
                } catch (InterruptedException | BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        AccountSnapshot snap = svc.getSnapshot(acc);
        log.info("[并发重复提交] accountVersion={} applied={} duplicate={} conflict={} 现金={}",
                snap.version(), appliedCount.get(), duplicateCount.get(),
                conflictCount.get(), snap.cash().toPlainString());

        assertEquals(1, appliedCount.get(), "同一业务请求并发提交必须只生效一次");
        assertEquals(n - 1, duplicateCount.get(), "其余并发提交必须被识别为重复");
        assertEquals(0, conflictCount.get());
        assertEquals(new BigDecimal("500"), snap.cash(), "重复提交不得重复入账");
        assertEquals(1, snap.version());
    }

    @Test
    void concurrentDuplicateTradeAppliesExactlyOnce() throws Exception {
        AccountRepository repo = new AccountRepository();
        MarginPolicy policy = new MarginPolicy();
        policy.setRate("AAA", new BigDecimal("0.5"));
        PortfolioService svc = new PortfolioService(repo, new MarketDataService(),
                new RiskCalculator(policy), policy);
        String acc = "ACC-DUP-TRD";
        svc.createAccount(acc);
        svc.transfer(acc, "d", TransferType.DEPOSIT, new BigDecimal("10000"), null);

        int n = 12;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        for (int t = 0; t < n; t++) {
            pool.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    OperationResult r = svc.adjustPosition(acc, "same-trade-id",
                            "AAA", TradeSide.BUY, new BigDecimal("10"),
                            new BigDecimal("100"), null);
                    if (r.applied()) {
                        applied.incrementAndGet();
                    }
                    if (r.duplicate()) {
                        duplicate.incrementAndGet();
                    }
                } catch (InterruptedException | BrokenBarrierException |
                         java.util.concurrent.TimeoutException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        AccountSnapshot snap = svc.getSnapshot(acc);
        log.info("[并发重复成交] applied={} duplicate={} version={} cash={} 持仓AAA={}",
                applied.get(), duplicate.get(), snap.version(),
                snap.cash().toPlainString(),
                snap.position("AAA") == null ? "无" : snap.position("AAA").qty().toPlainString());

        assertEquals(1, applied.get(), "同一成交请求并发提交必须只成交一次");
        assertEquals(n - 1, duplicate.get());
        assertEquals(new BigDecimal("9000"), snap.cash(), "重复成交不得重复扣款");
        assertEquals(new BigDecimal("10"), snap.position("AAA").qty(), "重复成交不得重复加持仓");
        assertEquals(2, snap.version(), "入金 1 次 + 成交 1 次 => version=2");
    }

    @Test
    void sequentialDuplicateSubmissionReturnsFirstResult() {
        PortfolioService svc = newService();
        String acc = "ACC-DUP2";
        svc.createAccount(acc);

        OperationResult first = svc.transfer(acc, "r1", TransferType.DEPOSIT,
                new BigDecimal("300"), null);
        OperationResult second = svc.transfer(acc, "r1", TransferType.DEPOSIT,
                new BigDecimal("300"), null);
        OperationResult third = svc.transfer(acc, "r1", TransferType.WITHDRAW,
                new BigDecimal("999"), null); // 连请求内容不同也按同一业务请求幂等处理

        log.info("[串行重复提交] first(applied={},v={}) second(dup={},v={}) third(dup={},v={})",
                first.applied(), first.accountVersion(),
                second.duplicate(), second.accountVersion(),
                third.duplicate(), third.accountVersion());

        assertTrue(first.applied());
        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertTrue(third.duplicate());
        assertEquals(first.accountVersion(), second.accountVersion());
        assertEquals(first.accountVersion(), third.accountVersion());
        assertEquals(new BigDecimal("300"), svc.getSnapshot(acc).cash());
    }

    @Test
    void staleExpectedVersionIsRejected() {
        PortfolioService svc = newService();
        String acc = "ACC-VER";
        svc.createAccount(acc);
        svc.transfer(acc, "v1", TransferType.DEPOSIT, new BigDecimal("100"), null);
        AccountSnapshot snap = svc.getSnapshot(acc);
        log.info("[版本冲突] 当前 version={}，提交携带 expectedVersion=0 的过期请求", snap.version());

        RiskEngineException ex = org.junit.jupiter.api.Assertions.assertThrows(
                RiskEngineException.class,
                () -> svc.transfer(acc, "v-stale", TransferType.WITHDRAW,
                        new BigDecimal("10"), 0L));
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getCode());
        // 冲突后账户保持原状，未产生版本推进
        assertEquals(1, svc.getSnapshot(acc).version());
        assertEquals(new BigDecimal("100"), svc.getSnapshot(acc).cash());
        log.info("[版本冲突] 按预期拒绝 code={}，账户仍为 version=1 cash=100", ex.getCode());
    }

    @Test
    void failedRequestCanBeRetriedWithSameIdAfterCorrection() {
        PortfolioService svc = newService();
        String acc = "ACC-RETRY";
        svc.createAccount(acc);
        svc.transfer(acc, "d", TransferType.DEPOSIT, new BigDecimal("50"), null);

        // 首次超额出金失败
        RiskEngineException e = assertThrows(RiskEngineException.class,
                () -> svc.transfer(acc, "w-retry", TransferType.WITHDRAW,
                        new BigDecimal("80"), null));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.getCode());
        assertEquals(1, svc.getSnapshot(acc).version(), "失败不推进版本");

        // 用同一 requestId 改为合法金额重试：应成功，且只生效一次
        OperationResult r = svc.transfer(acc, "w-retry", TransferType.WITHDRAW,
                new BigDecimal("30"), null);
        assertTrue(r.applied());
        assertFalse(r.duplicate());
        assertEquals(2, r.accountVersion());
        assertEquals(new BigDecimal("20"), svc.getSnapshot(acc).cash());

        // 成功后再重放即为重复，不二次生效
        OperationResult again = svc.transfer(acc, "w-retry", TransferType.WITHDRAW,
                new BigDecimal("30"), null);
        assertTrue(again.duplicate());
        assertEquals(new BigDecimal("20"), svc.getSnapshot(acc).cash());
        log.info("[失败重试] 超额出金失败后同 requestId 合法重试成功，再重放被识别为重复");
    }

    @Test
    void insufficientFundsAndPositionsAreRejectedWithDistinctReasons() {
        // 需要保证金率配置：用可注入的方式构造 service
        AccountRepository repo = new AccountRepository();
        MarketDataService market = new MarketDataService();
        MarginPolicy policy = new MarginPolicy();
        policy.setRate("AAA", new BigDecimal("0.5"));
        PortfolioService svc = new PortfolioService(repo, market,
                new RiskCalculator(policy), policy);
        String acc = "ACC-REJ";
        svc.createAccount(acc);
        svc.transfer(acc, "d", TransferType.DEPOSIT, new BigDecimal("100"), null);

        // 超额出金
        RiskEngineException e1 = assertThrows(RiskEngineException.class,
                () -> svc.transfer(acc, "w", TransferType.WITHDRAW,
                        new BigDecimal("101"), null));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e1.getCode());
        log.info("[超额出金] code={} msg={}", e1.getCode(), e1.getMessage());

        // 超额买入（全款 1000 > 权益 100，rate=0.5：成交后可用 = 100 - 1000*0.5 = -400）
        RiskEngineException e2 = assertThrows(RiskEngineException.class,
                () -> svc.adjustPosition(acc, "b", "AAA", TradeSide.BUY,
                        new BigDecimal("10"), new BigDecimal("100"), null));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e2.getCode());
        log.info("[超额买入] code={} msg={}", e2.getCode(), e2.getMessage());

        // 卖出不存在的持仓
        RiskEngineException e3 = assertThrows(RiskEngineException.class,
                () -> svc.adjustPosition(acc, "s", "AAA", TradeSide.SELL,
                        new BigDecimal("1"), new BigDecimal("100"), null));
        assertEquals(ErrorCode.INSUFFICIENT_POSITION, e3.getCode());
        log.info("[超仓卖出] code={} msg={}", e3.getCode(), e3.getMessage());

        // 全部失败：无任何版本推进，现金不变、无持仓
        AccountSnapshot snap = svc.getSnapshot(acc);
        log.info("[拒绝后状态] version={} cash={} positions={}",
                snap.version(), snap.cash(), snap.positions().size());
        assertEquals(1, snap.version());
        assertEquals(new BigDecimal("100"), snap.cash());
        assertTrue(snap.positions().isEmpty());
    }

    @Test
    void oddLotAverageCostDivisionDoesNotFail() {
        AccountRepository repo = new AccountRepository();
        MarginPolicy policy = new MarginPolicy();
        policy.setRate("AAA", new BigDecimal("0.2"));
        PortfolioService svc = new PortfolioService(repo, new MarketDataService(),
                new RiskCalculator(policy), policy);
        String acc = "ACC-ODD";
        svc.createAccount(acc);
        svc.transfer(acc, "d", TransferType.DEPOSIT, new BigDecimal("1000"), null);

        // 3 股 @10 后再买 3 股 @11：均价 = (30+33)/6 = 10.5（整除）
        svc.adjustPosition(acc, "b1", "AAA", TradeSide.BUY,
                new BigDecimal("3"), new BigDecimal("10"), null);
        svc.adjustPosition(acc, "b2", "AAA", TradeSide.BUY,
                new BigDecimal("3"), new BigDecimal("11"), null);
        // 再买 7 股 @10.1：总成本 63 + 70.7 = 133.7，数量 13，133.7/13 除不尽
        svc.adjustPosition(acc, "b3", "AAA", TradeSide.BUY,
                new BigDecimal("7"), new BigDecimal("10.1"), null);

        AccountSnapshot snap = svc.getSnapshot(acc);
        Position p = snap.position("AAA");
        log.info("[零股均价] qty={} avgCost={} cash={} version={}",
                p.qty().toPlainString(), p.avgCost().toPlainString(),
                snap.cash().toPlainString(), snap.version());
        assertEquals(new BigDecimal("13"), p.qty(), "数量守恒：3+3+7=13");
        assertEquals(new BigDecimal("866.3"), snap.cash(), "资金守恒：1000-30-33-70.7=866.3");
        assertEquals(new BigDecimal("10.2846153846"), p.avgCost(),
                "除不尽时按 10 位小数 HALF_UP，交易不整体失败");
    }

    @Test
    void perThreadSymbolsConserveQuantitiesAndCashUnderConcurrency() throws Exception {
        AccountRepository repo = new AccountRepository();
        MarginPolicy policy = new MarginPolicy();
        PortfolioService svc = new PortfolioService(repo, new MarketDataService(),
                new RiskCalculator(policy), policy);
        String acc = "ACC-TRD";
        svc.createAccount(acc);
        svc.transfer(acc, "init", TransferType.DEPOSIT,
                new BigDecimal("100000000"), null);

        for (int t = 0; t < THREADS; t++) {
            policy.setRate("S" + t, new BigDecimal("0.2"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicLong buyValue = new AtomicLong();
        AtomicLong sellValue = new AtomicLong();
        AtomicInteger trades = new AtomicInteger();
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                String symbol = "S" + tid;
                long price = 10 + tid;
                for (int i = 0; i < ROUNDS; i++) {
                    // 先买 10 股、再卖 10 股，配对进行，现金充足，全部成功
                    svc.adjustPosition(acc, "b-" + tid + "-" + i, symbol,
                            TradeSide.BUY, BigDecimal.TEN, BigDecimal.valueOf(price), null);
                    buyValue.addAndGet(10 * price);
                    svc.adjustPosition(acc, "s-" + tid + "-" + i, symbol,
                            TradeSide.SELL, BigDecimal.TEN, BigDecimal.valueOf(price), null);
                    sellValue.addAndGet(10 * price);
                    trades.addAndGet(2);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));

        AccountSnapshot snap = svc.getSnapshot(acc);
        log.info("[并发交易压力] accountVersion={} trades={} buyValue={} sellValue={} 现金={}",
                snap.version(), trades.get(), buyValue.get(), sellValue.get(),
                snap.cash().toPlainString());

        // 买卖等额配对：现金回到初始值
        assertEquals(new BigDecimal("100000000"), snap.cash(),
                "资金守恒：等额买卖配对后现金必须回到初始值");
        // 持仓数量守恒：每标的 10 买 - 10 卖 = 0，全部清空
        for (Position p : snap.positions().values()) {
            assertEquals(0, p.qty().signum(), "配对结束不应残留持仓：" + p.symbol());
        }
        assertTrue(snap.positions().isEmpty(), "配对交易结束后持仓必须全部平掉");
        // 版本推进次数 = 初始入金 1 + 每笔交易
        assertEquals(1 + trades.get(), snap.version(),
                "每次成功交易必须恰好推进一个版本");
    }
}
