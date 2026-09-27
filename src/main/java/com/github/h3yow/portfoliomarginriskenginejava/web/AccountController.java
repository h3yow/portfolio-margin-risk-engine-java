package com.github.h3yow.portfoliomarginriskenginejava.web;

import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskReport;
import com.github.h3yow.portfoliomarginriskenginejava.market.InMemoryMarketDataProvider;
import com.github.h3yow.portfoliomarginriskenginejava.market.Quote;
import com.github.h3yow.portfoliomarginriskenginejava.service.AccountCommandService;
import com.github.h3yow.portfoliomarginriskenginejava.service.RiskQueryService;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.CashTransferRequest;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.CommandResponse;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.DepositRequest;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.OpenAccountRequest;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.PositionTransferRequest;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.QuoteUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地账户变更、行情维护与风险/强平查询接口。 */
@RestController
@RequestMapping("/api")
public class AccountController {

    private final AccountCommandService commands;
    private final RiskQueryService queries;
    private final InMemoryMarketDataProvider marketData;

    public AccountController(AccountCommandService commands, RiskQueryService queries,
                             InMemoryMarketDataProvider marketData) {
        this.commands = commands;
        this.queries = queries;
        this.marketData = marketData;
    }

    @PostMapping("/accounts")
    public CommandResponse open(@RequestBody OpenAccountRequest req) {
        AccountCommandService.CommandOutcome out =
                commands.openAccount(req.requestId(), req.accountId(), req.initialCash());
        return new CommandResponse(out.version(), out.replayed(), out.summary());
    }

    @PostMapping("/deposits")
    public CommandResponse deposit(@RequestBody DepositRequest req) {
        AccountCommandService.CommandOutcome out =
                commands.deposit(req.requestId(), req.accountId(), req.amount());
        return new CommandResponse(out.version(), out.replayed(), out.summary());
    }

    @PostMapping("/transfers/cash")
    public CommandResponse transferCash(@RequestBody CashTransferRequest req) {
        AccountCommandService.CommandOutcome out = commands.transferCash(
                req.requestId(), req.fromAccountId(), req.toAccountId(), req.amount());
        return new CommandResponse(out.version(), out.replayed(), out.summary());
    }

    @PostMapping("/transfers/positions")
    public CommandResponse transferPosition(@RequestBody PositionTransferRequest req) {
        AccountCommandService.CommandOutcome out = commands.transferPosition(
                req.requestId(), req.fromAccountId(), req.toAccountId(),
                req.symbol(), req.quantity());
        return new CommandResponse(out.version(), out.replayed(), out.summary());
    }

    @PostMapping("/quotes")
    public CommandResponse updateQuote(@RequestBody QuoteUpdateRequest req) {
        marketData.update(new Quote(req.symbol(), req.price(), System.currentTimeMillis()));
        return new CommandResponse(0L, false, "行情已更新: " + req.symbol());
    }

    @GetMapping("/accounts/{accountId}/risk")
    public RiskReport risk(@PathVariable String accountId) {
        return queries.evaluateAccount(accountId);
    }
}
