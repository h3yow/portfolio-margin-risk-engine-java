package com.github.h3yow.portfoliomarginriskenginejava.service;

import com.github.h3yow.portfoliomarginriskenginejava.ledger.AccountLedger;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.IdempotencyTable;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketDataProvider;
import com.github.h3yow.portfoliomarginriskenginejava.risk.RiskCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/** 本地引擎装配：单例账本、幂等表、命令服务与风险计算器。 */
@Configuration
public class EngineConfig {

    @Bean
    public AccountLedger accountLedger() {
        return new AccountLedger();
    }

    @Bean
    public IdempotencyTable idempotencyTable() {
        return new IdempotencyTable();
    }

    @Bean
    public AccountCommandService accountCommandService(AccountLedger ledger,
                                                       IdempotencyTable idempotencyTable) {
        return new AccountCommandService(ledger, idempotencyTable);
    }

    @Bean
    public RiskCalculator riskCalculator(MarketDataProvider marketData,
                                         @Value("${risk.maintenance-margin-rate:0.25}")
                                         BigDecimal maintenanceMarginRate) {
        return new RiskCalculator(marketData, maintenanceMarginRate);
    }
}
