package com.github.h3yow.portfoliomarginriskenginejava.ledger;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskEngineException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * 内存账户账本。
 *
 * <p><b>一致性模型</b></p>
 * <ul>
 *   <li>单一 {@link ReentrantReadWriteLock}：所有写事务串行执行，读操作彼此并行但与写互斥。</li>
 *   <li>全局单调递增版本号 {@code version}：每次成功提交的写事务恰好 +1，
 *       快照中的每个账户都携带该版本号，任何风险结论都可追溯到其依据的版本。</li>
 *   <li>读路径产出的是深拷贝不可变 {@link AccountSnapshot}，风险计算期间账户可以继续被修改，
 *       但计算所见永远是同一版本的完整状态，不可能读到部分更新。</li>
 *   <li>写路径在入口对全部账户做深拷贝；事务中抛异常则整体回滚并重新抛出，
 *       杜绝"扣了 A 没加 B"式的部分生效。</li>
 * </ul>
 */
public class AccountLedger {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Map<String, AccountState> accounts = new LinkedHashMap<>();
    private long version = 0L;

    public AccountLedger() {
    }

    public long version() {
        lock.readLock().lock();
        try {
            return version;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 取整个账本某一版本的完整不可变快照。 */
    public LedgerSnapshot snapshot() {
        lock.readLock().lock();
        try {
            return buildSnapshot();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 取单账户快照；账户不存在是可区分错误，绝不返回空状态冒充零余额。 */
    public AccountSnapshot snapshotAccount(String accountId) {
        lock.readLock().lock();
        try {
            AccountState state = accounts.get(accountId);
            if (state == null) {
                throw new RiskEngineException(RiskErrorCode.ACCOUNT_NOT_FOUND,
                        "账户不存在: " + accountId);
            }
            return state.toSnapshot(version);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 在写锁内原子执行一次事务。事务通过 {@link WriteContext} 读取/变更账户；
     * 任一步骤失败则全部变更回滚，成功则版本号 +1 后对外可见。
     */
    public <T> T write(Function<WriteContext, T> mutation) {
        lock.writeLock().lock();
        try {
            // 备份的是"上一个已提交状态"，其脏标志必须已清，避免失败回滚后残留标脏
            for (AccountState s : accounts.values()) {
                s.clearDirty();
            }
            Map<String, AccountState> backup = deepCopy();
            try {
                T result = mutation.apply(new WriteContext());
                // 仅当确有账户状态变更时才推进版本；纯幂等回放不产生新版本
                boolean changed = accounts.values().stream().anyMatch(AccountState::dirty);
                if (changed) {
                    version++;
                }
                for (AccountState s : accounts.values()) {
                    s.clearDirty();
                }
                return result;
            } catch (RuntimeException ex) {
                accounts.clear();
                for (Map.Entry<String, AccountState> e : backup.entrySet()) {
                    accounts.put(e.getKey(), new AccountState(e.getValue()));
                }
                throw ex;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Map<String, AccountState> deepCopy() {
        Map<String, AccountState> copy = new LinkedHashMap<>();
        for (Map.Entry<String, AccountState> e : accounts.entrySet()) {
            copy.put(e.getKey(), new AccountState(e.getValue()));
        }
        return copy;
    }

    private LedgerSnapshot buildSnapshot() {
        List<AccountSnapshot> list = new ArrayList<>(accounts.size());
        for (AccountState state : accounts.values()) {
            list.add(state.toSnapshot(version));
        }
        return new LedgerSnapshot(version, List.copyOf(list));
    }

    /** 写事务上下文：仅在 {@link #write} 回调内有效。 */
    public final class WriteContext {

        private WriteContext() {
        }

        public Set<String> accountIds() {
            return Set.copyOf(accounts.keySet());
        }

        public boolean exists(String accountId) {
            return accounts.containsKey(accountId);
        }

        public AccountState require(String accountId) {
            AccountState state = accounts.get(accountId);
            if (state == null) {
                throw new RiskEngineException(RiskErrorCode.ACCOUNT_NOT_FOUND,
                        "账户不存在: " + accountId);
            }
            return state;
        }

        public AccountState create(String accountId, java.math.BigDecimal initialCash) {
            if (accounts.containsKey(accountId)) {
                throw new RiskEngineException(RiskErrorCode.ACCOUNT_ALREADY_EXISTS,
                        "账户已存在: " + accountId);
            }
            AccountState state = new AccountState(accountId, initialCash);
            accounts.put(accountId, state);
            return state;
        }

        /** 事务提交后的新版本号（供命令结果携带，便于追溯）。 */
        public long committedVersion() {
            return version + 1;
        }
    }
}
