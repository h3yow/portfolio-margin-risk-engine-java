package com.github.h3yow.portfoliomarginriskenginejava.service;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskReport;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.AccountLedger;
import com.github.h3yow.portfoliomarginriskenginejava.risk.RiskCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 风险查询服务。
 *
 * <p>{@link AccountLedger#snapshotAccount(String)} 在与写事务互斥的读锁内取出某账户的
 * 完整不可变快照，之后纯计算可以在锁外进行。因此每次结论都绑定一个确定的
 * 账本版本号，快照之后发生的并发变更不会影响本次结论。</p>
 */
@Service
public class RiskQueryService {

    private static final Logger log = LoggerFactory.getLogger(RiskQueryService.class);

    private final AccountLedger ledger;
    private final RiskCalculator calculator;

    public RiskQueryService(AccountLedger ledger, RiskCalculator calculator) {
        this.ledger = ledger;
        this.calculator = calculator;
    }

    public RiskReport evaluateAccount(String accountId) {
        AccountSnapshot snapshot = ledger.snapshotAccount(accountId);
        RiskReport report = calculator.evaluate(snapshot);
        log.info("风险查询输入: accountId={}, 状态版本={}, defined={}, equity={}, mm={}, "
                        + "available={}, liquidation={}, reasons={}, 判定依据=[{}]",
                accountId, report.version(), report.defined(),
                report.equity(), report.maintenanceMargin(), report.availableMargin(),
                report.liquidation(), report.reasons(), report.basis());
        return report;
    }
}
