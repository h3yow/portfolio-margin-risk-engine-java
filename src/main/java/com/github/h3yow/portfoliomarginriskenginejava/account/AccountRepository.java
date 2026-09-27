package com.github.h3yow.portfoliomarginriskenginejava.account;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.error.ErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.error.RiskEngineException;
import org.springframework.stereotype.Repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 账户仓储：每账户一个 {@link AccountSnapshot} 不可变快照引用。
 *
 * <p>所有变更都经过 CAS 循环：变换函数基于某完整快照产出下一个完整快照，
 * CAS 成功才生效；失败则基于最新快照重试。因此：</p>
 * <ul>
 *   <li>读到的永远是某个完整版本，不可能读到部分更新的中间状态；</li>
 *   <li>不会丢失任何一笔并发提交（lost update 被 CAS 排除）；</li>
 *   <li>业务校验（余额/持仓不足等）在 transition 内执行，失败抛异常则本次完全不生效。</li>
 * </ul>
 */
@Repository
public class AccountRepository {

    private final ConcurrentHashMap<String, AccountSnapshot> accounts = new ConcurrentHashMap<>();

    /** 开户；重复开户以 {@link ErrorCode#ACCOUNT_ALREADY_EXISTS} 明确拒绝。 */
    public AccountSnapshot create(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new RiskEngineException(ErrorCode.INVALID_ARGUMENT, "accountId 不能为空");
        }
        AccountSnapshot initial = AccountSnapshot.empty(accountId);
        AccountSnapshot existing = accounts.putIfAbsent(accountId, initial);
        if (existing != null) {
            throw new RiskEngineException(ErrorCode.ACCOUNT_ALREADY_EXISTS,
                    "账户已存在，accountId=" + accountId + "，version=" + existing.version());
        }
        return initial;
    }

    /** 读取当前完整快照；账户不存在显式失败。 */
    public AccountSnapshot get(String accountId) {
        AccountSnapshot s = accounts.get(accountId);
        if (s == null) {
            throw new RiskEngineException(ErrorCode.ACCOUNT_NOT_FOUND,
                    "账户不存在，accountId=" + accountId);
        }
        return s;
    }

    /**
     * 基于当前快照原子地迁移到下一快照，版本号 +1。
     *
     * @param transition 纯函数：入参为某完整快照，返回下一完整快照；
     *                   不允许返回 null，业务拒绝请直接抛 {@link RiskEngineException}
     */
    public AccountSnapshot mutate(String accountId, UnaryOperator<AccountSnapshot> transition) {
        while (true) {
            AccountSnapshot current = get(accountId);
            AccountSnapshot next = transition.apply(current);
            if (next == null) {
                throw new IllegalStateException("状态迁移不允许返回 null");
            }
            if (accounts.replace(accountId, current, next)) {
                return next;
            }
            // CAS 失败：有并发变更先落地，基于最新快照整体重试，保证不丢更新
        }
    }
}
