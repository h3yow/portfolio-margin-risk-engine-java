package com.github.h3yow.portfoliomarginriskenginejava.web;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.OperationResult;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskDecision;
import com.github.h3yow.portfoliomarginriskenginejava.domain.TradeSide;
import com.github.h3yow.portfoliomarginriskenginejava.domain.TransferType;
import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.market.MarketDataService;
import com.github.h3yow.portfoliomarginriskenginejava.risk.MarginPolicy;
import com.github.h3yow.portfoliomarginriskenginejava.service.PortfolioService;
import com.github.h3yow.portfoliomarginriskenginejava.web.dto.Dtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 入口：开户、资金划转、持仓调整、行情/保证金率维护、风险查询。
 */
@RestController
@RequestMapping("/api")
public class RiskController {

    private final PortfolioService portfolioService;
    private final MarketDataService marketDataService;
    private final MarginPolicy marginPolicy;

    public RiskController(PortfolioService portfolioService,
                          MarketDataService marketDataService,
                          MarginPolicy marginPolicy) {
        this.portfolioService = portfolioService;
        this.marketDataService = marketDataService;
        this.marginPolicy = marginPolicy;
    }

    @PostMapping("/accounts")
    public AccountSnapshot createAccount(@RequestBody Dtos.CreateAccountRequest request) {
        if (request == null || request.accountId() == null || request.accountId().isBlank()) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "accountId 不能为空");
        }
        return portfolioService.createAccount(request.accountId());
    }

    @GetMapping("/accounts/{accountId}")
    public AccountSnapshot snapshot(@PathVariable String accountId) {
        return portfolioService.getSnapshot(accountId);
    }

    @PostMapping("/transfers")
    public OperationResult transfer(@RequestBody Dtos.TransferRequest request) {
        require(request != null, "请求体不能为空");
        return portfolioService.transfer(
                request.accountId(), request.requestId(),
                parseEnum(TransferType.class, request.type(), "type"),
                request.amount(), request.expectedVersion());
    }

    @PostMapping("/positions/adjust")
    public OperationResult adjustPosition(@RequestBody Dtos.AdjustPositionRequest request) {
        require(request != null, "请求体不能为空");
        return portfolioService.adjustPosition(
                request.accountId(), request.requestId(), request.symbol(),
                parseEnum(TradeSide.class, request.side(), "side"),
                request.qty(), request.price(), request.expectedVersion());
    }

    @GetMapping("/accounts/{accountId}/risk")
    public RiskDecision risk(@PathVariable String accountId) {
        return portfolioService.evaluateRisk(accountId);
    }

    @PutMapping("/market/quotes")
    public Object putQuote(@RequestBody Dtos.QuoteRequest request) {
        require(request != null, "请求体不能为空");
        return marketDataService.putQuote(request.symbol(), request.price());
    }

    @PutMapping("/margin-rates")
    public Object putMarginRate(@RequestBody Dtos.MarginRateRequest request) {
        require(request != null, "请求体不能为空");
        marginPolicy.setRate(request.symbol(), request.rate());
        return java.util.Map.of("symbol", request.symbol(), "rate", request.rate(), "stored", true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, message);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT,
                    field + " 取值非法: " + raw);
        }
    }
}
