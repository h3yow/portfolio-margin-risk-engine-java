package com.github.h3yow.portfoliomarginriskenginejava.ledger;

import com.github.h3yow.portfoliomarginriskenginejava.domain.AccountSnapshot;
import com.github.h3yow.portfoliomarginriskenginejava.domain.Position;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户可变状态。
 *
 * <p><b>并发契约</b>：所有变更方法只能在 {@link AccountLedger#write} 持有的写锁内调用。
 * 每次写事务中首个变更会把对象标记为 dirty，事务提交成功后由账本统一推进版本，
 * 从而保证一个事务内对同一账户的多处修改对外只呈现一次版本跃迁，不存在中间状态。</p>
 */
public final class AccountState {

    private final String accountId;
    private BigDecimal cash;
    private final Map<String, BigDecimal> positions = new LinkedHashMap<>();
    private boolean dirty;

    AccountState(String accountId, BigDecimal cash) {
        this.accountId = accountId;
        this.cash = cash;
        // 开户本身是一次状态变更，写事务提交时应推进版本
        this.dirty = true;
    }

    /** 深拷贝构造，用于写事务失败时回滚。 */
    AccountState(AccountState other) {
        this.accountId = other.accountId;
        this.cash = other.cash;
        this.positions.putAll(other.positions);
        this.dirty = other.dirty;
    }

    public String accountId() {
        return accountId;
    }

    public BigDecimal cash() {
        return cash;
    }

    public BigDecimal positionOf(String symbol) {
        return positions.getOrDefault(symbol, BigDecimal.ZERO);
    }

    /** 增加现金（delta 可正可负）；是否超额由命令服务在变更前完成校验。 */
    public void addCash(BigDecimal delta) {
        this.cash = this.cash.add(delta);
        this.dirty = true;
    }

    /** 调整某标的持仓（delta 可正可负）。 */
    public void adjustPosition(String symbol, BigDecimal delta) {
        BigDecimal next = positionOf(symbol).add(delta);
        if (next.signum() == 0) {
            positions.remove(symbol);
        } else {
            positions.put(symbol, next);
        }
        this.dirty = true;
    }

    boolean dirty() {
        return dirty;
    }

    void clearDirty() {
        this.dirty = false;
    }

    /** 生成带账本全局版本号的不可变快照。 */
    AccountSnapshot toSnapshot(long ledgerVersion) {
        List<Position> copy = new ArrayList<>(positions.size());
        for (Map.Entry<String, BigDecimal> e : positions.entrySet()) {
            copy.add(new Position(e.getKey(), e.getValue()));
        }
        return new AccountSnapshot(accountId, ledgerVersion, cash, List.copyOf(copy));
    }
}
