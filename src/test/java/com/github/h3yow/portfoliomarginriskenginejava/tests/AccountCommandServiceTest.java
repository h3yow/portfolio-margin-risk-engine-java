package com.github.h3yow.portfoliomarginriskenginejava.tests;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.AccountLedger;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.IdempotencyTable;
import com.github.h3yow.portfoliomarginriskenginejava.service.AccountCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 幂等、守恒、拒绝原因与失败回滚单测；日志打印输入、状态版本与判定依据。 */
class AccountCommandServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AccountCommandServiceTest.class);

    private AccountLedger ledger;
    private AccountCommandService commands;

    @BeforeEach
    void setUp() {
        ledger = new AccountLedger();
        commands = new AccountCommandService(ledger, new IdempotencyTable());
        commands.openAccount("r-open-a", "A", new BigDecimal("1000"));
        commands.openAccount("r-open-b", "B", new BigDecimal("200"));
        // 模拟一笔账本外结算，为 A 建立初始持仓 10 股 AAA（测试前置数据，单独一次写事务）
        ledger.write(ctx -> {
            ctx.require("A").adjustPosition("AAA", new BigDecimal("10"));
            return null;
        });
        // 合法划转 5 股 A -> B，之后 A=5, B=5
        commands.transferPosition("r-seed", "A", "B", "AAA", new BigDecimal("5"));
    }

    @Test
    void duplicateRequestIsReplayedAndAppliedOnce() {
        var first = commands.transferCash("dup-1", "A", "B", new BigDecimal("100"));
        var second = commands.transferCash("dup-1", "A", "B", new BigDecimal("100"));
        var third = commands.transferCash("dup-1", "A", "B", new BigDecimal("100"));
        log.info("[DUP] 输入 requestId=dup-1 amount=100, first.version={} replayed={}, "
                        + "second.version={} replayed={}, third.version={} replayed={}",
                first.version(), first.replayed(), second.version(), second.replayed(),
                third.version(), third.replayed());
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertTrue(third.replayed());
        assertEquals(first.version(), second.version());
        // 仅生效一次: A 1000-100=900, B 200+100=300
        assertEquals(new BigDecimal("900"), ledger.snapshotAccount("A").cash());
        assertEquals(new BigDecimal("300"), ledger.snapshotAccount("B").cash());
        log.info("[DUP] 判定依据: 三次提交同一 requestId，仅首次 version={} 生效，系统现金总量守恒={}",
                first.version(),
                ledger.snapshotAccount("A").cash().add(ledger.snapshotAccount("B").cash()));
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsConflict() {
        commands.transferCash("dup-2", "A", "B", new BigDecimal("50"));
        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> commands.transferCash("dup-2", "A", "B", new BigDecimal("60")));
        log.info("[CONFLICT] 输入 requestId=dup-2 第二次金额=60, 拒绝原因={}", ex.getCode());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", ex.getCode().name());
        // 冲突请求不得生效
        assertEquals(new BigDecimal("950"), ledger.snapshotAccount("A").cash());
    }

    @Test
    void insufficientFundsRejectedWithDistinctReasonAndNoPartialEffect() {
        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> commands.transferCash("r-over", "B", "A", new BigDecimal("999999")));
        log.info("[OVERDRAW] 输入 from=B amount=999999 cash=200, 拒绝原因={} msg={}",
                ex.getCode(), ex.getMessage());
        assertEquals("INSUFFICIENT_FUNDS", ex.getCode().name());
        assertEquals(new BigDecimal("200"), ledger.snapshotAccount("B").cash());
        assertEquals(new BigDecimal("1000"), ledger.snapshotAccount("A").cash());
        // 被拒绝的事务不得推进版本号（2次开户+1次结算建仓+1次合法划转=4）
        assertEquals(4L, ledger.version());
    }

    @Test
    void insufficientPositionRejectedAndConserved() {
        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> commands.transferPosition("r-pos-over", "B", "A", "AAA", new BigDecimal("6")));
        log.info("[OVERPOS] 输入 from=B symbol=AAA need=6 held=5, 拒绝原因={}", ex.getCode());
        assertEquals("INSUFFICIENT_POSITION", ex.getCode().name());
        assertEquals(new BigDecimal("5"), ledger.snapshotAccount("B").positionOf("AAA"));
        assertEquals(new BigDecimal("5"), ledger.snapshotAccount("A").positionOf("AAA"));
    }

    @Test
    void sameAccountTransferRejected() {
        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> commands.transferCash("r-self", "A", "A", new BigDecimal("10")));
        log.info("[SELF] 输入 from=A to=A, 拒绝原因={}", ex.getCode());
        assertEquals("SAME_ACCOUNT_TRANSFER", ex.getCode().name());
    }

    @Test
    void nonPositiveAmountRejected() {
        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> commands.deposit("r-zero", "A", BigDecimal.ZERO));
        log.info("[ZERO] 输入 amount=0, 拒绝原因={}", ex.getCode());
        assertEquals("NEGATIVE_AMOUNT", ex.getCode().name());
    }

    @Test
    void unknownAccountRejectedNotSilentlyCreated() {
        RiskEngineException ex = assertThrows(RiskEngineException.class,
                () -> commands.deposit("r-ghost", "GHOST", new BigDecimal("10")));
        log.info("[GHOST] 输入 accountId=GHOST, 拒绝原因={}", ex.getCode());
        assertEquals("ACCOUNT_NOT_FOUND", ex.getCode().name());
    }

    @Test
    void totalCashAndPositionsConservedAcrossTransfers() {
        commands.transferCash("r-c1", "A", "B", new BigDecimal("300"));
        commands.transferPosition("r-p1", "B", "A", "AAA", new BigDecimal("2"));
        AccountSnapshot a = ledger.snapshotAccount("A");
        AccountSnapshot b = ledger.snapshotAccount("B");
        BigDecimal totalCash = a.cash().add(b.cash());
        BigDecimal totalPos = a.positionOf("AAA").add(b.positionOf("AAA"));
        log.info("[CONSERVE] 输入: cashTransfer=300,posTransfer=2, 状态版本={}, totalCash={} (期望1200), "
                        + "totalPosition AAA={} (期望10), 判定依据: 划转在同事务内成对反向记账",
                ledger.version(), totalCash, totalPos);
        assertEquals(new BigDecimal("1200"), totalCash);
        assertEquals(0, totalPos.compareTo(new BigDecimal("10")));
        // 4个前置成功事务 + 2个 = 6；被拒绝事务不计版本
        assertEquals(6L, ledger.version());
    }
}
