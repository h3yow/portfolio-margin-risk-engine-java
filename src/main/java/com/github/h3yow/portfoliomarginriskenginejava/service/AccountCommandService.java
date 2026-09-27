package com.github.h3yow.portfoliomarginriskenginejava.service;

import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskEngineException;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.AccountLedger;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.AccountState;
import com.github.h3yow.portfoliomarginriskenginejava.ledger.IdempotencyTable;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 账户变更服务。
 *
 * <p>每个业务请求都带 {@code requestId}（见 {@link IdempotencyTable}）；
 * 所有变更都在一次 {@link AccountLedger#write} 事务中完成，与幂等判定处于同一把写锁：</p>
 * <ul>
 *   <li>资金划转：from 扣减与 to 增加同事务，系统现金总量守恒；余额不足按
 *       {@link RiskErrorCode#INSUFFICIENT_FUNDS} 拒绝，不会先扣后加。</li>
 *   <li>持仓划转：from 与 to 数量相反，系统内该标的持仓总量守恒；持仓不足按
 *       {@link RiskErrorCode#INSUFFICIENT_POSITION} 拒绝。</li>
 *   <li>金额/数量必须为正（用负号参数表达反向操作是歧义输入，按
 *       {@link RiskErrorCode#NEGATIVE_AMOUNT} 拒绝）。</li>
 *   <li>同账户自转、缺参数等冲突均按可区分原因拒绝。</li>
 * </ul>
 */
public class AccountCommandService {

    private final AccountLedger ledger;
    private final IdempotencyTable idempotency;

    public AccountCommandService(AccountLedger ledger, IdempotencyTable idempotency) {
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    public CommandOutcome openAccount(String requestId, String accountId, BigDecimal initialCash) {
        requireText(requestId, "requestId");
        requireText(accountId, "accountId");
        requireNonNull(initialCash, "initialCash");
        if (initialCash.signum() < 0) {
            throw new RiskEngineException(RiskErrorCode.NEGATIVE_AMOUNT,
                    "初始资金不得为负: " + initialCash);
        }
        String fp = "OPEN|" + accountId + "|" + initialCash.stripTrailingZeros().toPlainString();
        return execute(requestId, fp, ctx -> {
            ctx.create(accountId, initialCash);
            return "开户成功 accountId=" + accountId + " initialCash="
                    + initialCash.toPlainString() + " version=" + ctx.committedVersion();
        });
    }

    public CommandOutcome deposit(String requestId, String accountId, BigDecimal amount) {
        requireText(requestId, "requestId");
        requireText(accountId, "accountId");
        requirePositive(amount, "amount");
        String fp = "DEPOSIT|" + accountId + "|" + amount.stripTrailingZeros().toPlainString();
        return execute(requestId, fp, ctx -> {
            AccountState state = ctx.require(accountId);
            state.addCash(amount);
            return "入账成功 accountId=" + accountId + " +" + amount.toPlainString()
                    + " version=" + ctx.committedVersion();
        });
    }

    public CommandOutcome transferCash(String requestId, String fromAccountId, String toAccountId,
                                       BigDecimal amount) {
        requireText(requestId, "requestId");
        requireText(fromAccountId, "fromAccountId");
        requireText(toAccountId, "toAccountId");
        requirePositive(amount, "amount");
        if (fromAccountId.equals(toAccountId)) {
            throw new RiskEngineException(RiskErrorCode.SAME_ACCOUNT_TRANSFER,
                    "资金划转的转出与转入账户不得相同: " + fromAccountId);
        }
        String fp = "CASH|" + fromAccountId + "|" + toAccountId + "|"
                + amount.stripTrailingZeros().toPlainString();
        return execute(requestId, fp, ctx -> {
            AccountState from = ctx.require(fromAccountId);
            AccountState to = ctx.require(toAccountId);
            if (from.cash().compareTo(amount) < 0) {
                throw new RiskEngineException(RiskErrorCode.INSUFFICIENT_FUNDS,
                        "资金不足: account=" + fromAccountId + " cash=" + from.cash().toPlainString()
                                + " need=" + amount.toPlainString());
            }
            from.addCash(amount.negate());
            to.addCash(amount);
            return "资金划转成功 " + fromAccountId + " -" + amount.toPlainString() + " -> "
                    + toAccountId + " +" + amount.toPlainString()
                    + " version=" + ctx.committedVersion();
        });
    }

    public CommandOutcome transferPosition(String requestId, String fromAccountId, String toAccountId,
                                           String symbol, BigDecimal quantity) {
        requireText(requestId, "requestId");
        requireText(fromAccountId, "fromAccountId");
        requireText(toAccountId, "toAccountId");
        requireText(symbol, "symbol");
        requirePositive(quantity, "quantity");
        if (fromAccountId.equals(toAccountId)) {
            throw new RiskEngineException(RiskErrorCode.SAME_ACCOUNT_TRANSFER,
                    "持仓划转的转出与转入账户不得相同: " + fromAccountId);
        }
        String fp = "POSITION|" + fromAccountId + "|" + toAccountId + "|" + symbol + "|"
                + quantity.stripTrailingZeros().toPlainString();
        return execute(requestId, fp, ctx -> {
            AccountState from = ctx.require(fromAccountId);
            AccountState to = ctx.require(toAccountId);
            BigDecimal held = from.positionOf(symbol);
            if (held.compareTo(quantity) < 0) {
                throw new RiskEngineException(RiskErrorCode.INSUFFICIENT_POSITION,
                        "持仓不足: account=" + fromAccountId + " symbol=" + symbol + " held="
                                + held.toPlainString() + " need=" + quantity.toPlainString());
            }
            from.adjustPosition(symbol, quantity.negate());
            to.adjustPosition(symbol, quantity);
            return "持仓划转成功 " + fromAccountId + " -" + quantity.toPlainString()
                    + " " + symbol + " -> " + toAccountId + " +" + quantity.toPlainString()
                    + " version=" + ctx.committedVersion();
        });
    }

    /**
     * 在一次写事务中完成幂等检查与业务变更。
     * 重复请求回放首次结果（replayed=true），不触碰任何余额/持仓。
     */
    private CommandOutcome execute(String requestId, String payloadFingerprint,
                                   java.util.function.Function<AccountLedger.WriteContext, String> action) {
        return ledger.write(ctx -> {
            Optional<IdempotencyTable.Record> existing =
                    idempotency.check(requestId, payloadFingerprint);
            if (existing.isPresent()) {
                CommandOutcome original = (CommandOutcome) existing.get().result();
                return new CommandOutcome(original.version(), true, original.summary());
            }
            String summary = action.apply(ctx);
            long committedVersion = ctx.committedVersion();
            CommandOutcome outcome = new CommandOutcome(committedVersion, false, summary);
            idempotency.store(requestId, payloadFingerprint, outcome);
            return outcome;
        });
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new RiskEngineException(RiskErrorCode.INVALID_ARGUMENT, name + " 不能为空");
        }
    }

    private static void requireNonNull(BigDecimal value, String name) {
        if (value == null) {
            throw new RiskEngineException(RiskErrorCode.INVALID_ARGUMENT, name + " 不能为空");
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new RiskEngineException(RiskErrorCode.NEGATIVE_AMOUNT,
                    name + " 必须为正数，实际: " + value.toPlainString());
        }
    }

    /** 命令执行结果：version=提交后的账本版本，replayed=是否为重复请求回放。 */
    public record CommandOutcome(long version, boolean replayed, String summary) {
    }
}
