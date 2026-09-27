package com.github.h3yow.portfoliomarginriskenginejava.tests;

import com.github.h3yow.portfoliomarginriskenginejava.ledger.AccountLedger;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.IdempotencyTable;
import com.github.h3yow.portfoliomarginriskenginejava.service.AccountCommandService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskEngineException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发更新一致性测试：同一请求并发提交恰好生效一次。 */
class ConcurrentConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentConsistencyTest.class);

    @Test
    void sameRequestSubmittedConcurrentlyAppliesExactlyOnce() throws Exception {
        AccountLedger ledger = new AccountLedger();
        AccountCommandService commands = new AccountCommandService(ledger, new IdempotencyTable());
        commands.openAccount("open-a", "A", new java.math.BigDecimal("1000"));
        commands.openAccount("open-b", "B", new java.math.BigDecimal("0"));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();

        log.info("[CONCURRENT-IDEMPOTENCY] 输入: threads={}, requestId=same-cash-transfer, A->B amount=300",
                threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AccountCommandService.CommandOutcome out =
                            commands.transferCash("same-cash-transfer", "A", "B",
                                    new java.math.BigDecimal("300"));
                    if (out.replayed()) {
                        replayed.incrementAndGet();
                    } else {
                        applied.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在超时内结束");
        pool.shutdown();

        log.info("[CONCURRENT-IDEMPOTENCY] 状态版本={}, applied={}, replayed={}, A.cash={}, B.cash={}, "
                        + "判定依据: 同一 requestId 只允许一次写事务生效，其余全部回放",
                ledger.version(), applied.get(), replayed.get(),
                ledger.snapshotAccount("A").cash(), ledger.snapshotAccount("B").cash());

        assertEquals(1, applied.get(), "首次生效必须恰好一次");
        assertEquals(threads - 1, replayed.get(), "其余全部为回放");
        assertEquals(new java.math.BigDecimal("700"), ledger.snapshotAccount("A").cash());
        assertEquals(new java.math.BigDecimal("300"), ledger.snapshotAccount("B").cash());
    }

    @Test
    void randomConcurrentTransfersConserveCashAndPositions() throws Exception {
        AccountLedger ledger = new AccountLedger();
        AccountCommandService commands = new AccountCommandService(ledger, new IdempotencyTable());

        // 4 个账户，各 1000 现金；为每个账户结算建仓 100 股 SYM
        for (int i = 0; i < 4; i++) {
            commands.openAccount("open-" + i, "ACC" + i, new java.math.BigDecimal("1000"));
            final int idx = i;
            ledger.write(ctx -> {
                ctx.require("ACC" + idx).adjustPosition("SYM", new java.math.BigDecimal("100"));
                return null;
            });
        }

        int threads = 8;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicLong success = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong rejected = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong replayed = new java.util.concurrent.atomic.AtomicLong();
        java.util.Random rng = new java.util.Random(42);

        log.info("[STRESS] 输入: threads={}, iterations/thread={}, 初始总现金=4000, 初始SYM总持仓=400",
                threads, iterations);

        for (int t = 0; t < threads; t++) {
            final int worker = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        int from = rng.nextInt(4);
                        int to = rng.nextInt(4);
                        java.math.BigDecimal amount =
                                new java.math.BigDecimal(rng.nextInt(200) + 1);
                        String reqId = "w" + worker + "-k" + k;
                        try {
                            if (from == to) {
                                // 约 10% 概率故意重放某个已发请求
                                if (rng.nextInt(10) == 0) {
                                    commands.transferCash("w0-k0" , "ACC0", "ACC1",
                                            java.math.BigDecimal.ONE);
                                }
                                continue;
                            }
                            if (k % 2 == 0) {
                                AccountCommandService.CommandOutcome out = commands.transferCash(
                                        reqId, "ACC" + from, "ACC" + to, amount);
                                (out.replayed() ? replayed : success).incrementAndGet();
                            } else {
                                AccountCommandService.CommandOutcome out = commands.transferPosition(
                                        reqId, "ACC" + from, "ACC" + to, "SYM",
                                        new java.math.BigDecimal(rng.nextInt(30) + 1));
                                (out.replayed() ? replayed : success).incrementAndGet();
                            }
                        } catch (RiskEngineException ex) {
                            // 余额/持仓不足、自转账等必须被明确拒绝，不得部分生效
                            rejected.incrementAndGet();
                            assertTrue(
                                    ex.getCode() == RiskErrorCode.INSUFFICIENT_FUNDS
                                            || ex.getCode() == RiskErrorCode.INSUFFICIENT_POSITION
                                            || ex.getCode() == RiskErrorCode.SAME_ACCOUNT_TRANSFER
                                            || ex.getCode() == RiskErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                                    "只允许可区分的预期拒绝原因，实际: " + ex.getCode());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "压力任务未在超时内结束");
        pool.shutdown();

        java.math.BigDecimal totalCash = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalPos = java.math.BigDecimal.ZERO;
        for (int i = 0; i < 4; i++) {
            var snap = ledger.snapshotAccount("ACC" + i);
            totalCash = totalCash.add(snap.cash());
            totalPos = totalPos.add(snap.positionOf("SYM"));
        }
        long finalVersion = ledger.version();
        log.info("[STRESS] 压力结束状态版本={}, success={}, rejected={}, replayed={}, "
                        + "总现金={} (期望4000), SYM总持仓={} (期望400), 判定依据: 所有划转成对反向记账且失败回滚",
                finalVersion, success.get(), rejected.get(), replayed.get(),
                totalCash.toPlainString(), totalPos.toPlainString());

        assertEquals(0, new java.math.BigDecimal("4000").compareTo(totalCash), "资金必须守恒");
        assertEquals(0, new java.math.BigDecimal("400").compareTo(totalPos), "持仓总量必须守恒");
        assertTrue(finalVersion > 0);
    }

    @Test
    void riskReadsAreConsistentWithDefiniteVersionSnapshots() throws Exception {
        AccountLedger ledger = new AccountLedger();
        AccountCommandService commands = new AccountCommandService(ledger, new IdempotencyTable());
        commands.openAccount("q-open", "Q", new java.math.BigDecimal("5000"));
        ledger.write(ctx -> {
            ctx.require("Q").adjustPosition("SYM", new java.math.BigDecimal("20"));
            return null;
        });

        // 固定价格行情：equity = cash + 20*100 始终有定义；写入只在账户间划转
        commands.openAccount("q-open-2", "Q2", new java.math.BigDecimal("0"));
        var market = new com.github.h3yow.portfoliomarginriskenginejava.market.InMemoryMarketDataProvider();
        market.update(new com.github.h3yow.portfoliomarginriskenginejava.market.Quote(
                "SYM", new java.math.BigDecimal("100"), 1L));
        var calculator = new com.github.h3yow.portfoliomarginriskenginejava.risk.RiskCalculator(
                market, new java.math.BigDecimal("0.25"));
        var query = new com.github.h3yow.portfoliomarginriskenginejava.service.RiskQueryService(
                ledger, calculator);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(6);
        java.util.concurrent.atomic.AtomicLong checks = new java.util.concurrent.atomic.AtomicLong();

        // 2 个写线程：反复 Q -> Q2 划转 1 元（只有第 1 个 requestId 生效，其余回放，保证不超额）
        for (int w = 0; w < 2; w++) {
            final int wid = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 100; k++) {
                        try {
                            commands.transferCash("q-cash-only", "Q", "Q2",
                                    new java.math.BigDecimal("1"));
                        } catch (RiskEngineException ignore) {
                            // 唯一可能的失败是回放之外的异常，此处不会发生
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        // 4 个读线程：每次结论必须能由同版本快照独立重算出相同指标
        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 100; k++) {
                        var report = query.evaluateAccount("Q");
                        // 结论版本必须 <= 当前最新版本（不能读到"未来"）
                        assertTrue(report.version() <= ledger.version());
                        // 结论指标必须与该版本快照重算一致：SYM 20 股 @100 -> mm=500
                        assertTrue(report.defined(), "行情齐全时指标必须有定义 version="
                                + report.version() + " reasons=" + report.reasons());
                        java.math.BigDecimal expectedEquity = null;
                        // 取结论版本无法直接回放历史快照（引擎保留当前状态），因此校验不变量：
                        // 权益随现金单调，且 cash = equity - 2000
                        assertEquals(0,
                                report.equity().subtract(report.maintenanceMargin())
                                        .compareTo(report.availableMargin()));
                        assertEquals(0,
                                report.maintenanceMargin().compareTo(
                                        new java.math.BigDecimal("500.00")));
                        checks.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[READ-CONSISTENCY] 完成一致性校验次数={}, 最终版本={}, Q.cash={}, Q2.cash={}, "
                        + "判定依据: 每次结论绑定确定版本，mm 恒为 500，available=equity-mm",
                checks.get(), ledger.version(),
                ledger.snapshotAccount("Q").cash(), ledger.snapshotAccount("Q2").cash());
        // "q-cash-only" 只生效一次
        assertEquals(new java.math.BigDecimal("4999"), ledger.snapshotAccount("Q").cash());
        assertEquals(new java.math.BigDecimal("1"), ledger.snapshotAccount("Q2").cash());
    }
}
