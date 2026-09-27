package com.github.h3yow.portfoliomarginriskenginejava.ledger;

import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskErrorCode;
import com.github.h3yow.portfoliomarginriskenginejava.domain.RiskEngineException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等记录表。
 *
 * <p>每个业务请求必须携带 {@code requestId}：</p>
 * <ul>
 *   <li>同 requestId + 同载荷指纹：重复提交，直接回放首次结果，不再产生任何资金/持仓变动；</li>
 *   <li>同 requestId + 不同载荷指纹：请求冲突，以 {@link RiskErrorCode#IDEMPOTENCY_KEY_CONFLICT} 明确拒绝；</li>
 *   <li>新 requestId：正常执行并登记结果。</li>
 * </ul>
 *
 * <p>本表的所有访问都发生在 {@link AccountLedger#write} 的写锁内，
 * 因此登记与执行对其他写事务原子可见，并发重复提交只有一个事务生效。</p>
 */
public class IdempotencyTable {

    private final Map<String, Record> records = new ConcurrentHashMap<>();

    public IdempotencyTable() {
    }

    /** 已存在且指纹一致：重复请求；已存在但指纹不同：请求冲突。不存在返回 empty。 */
    public Optional<Record> check(String requestId, String payloadFingerprint) {
        Record existing = records.get(requestId);
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.payloadFingerprint().equals(payloadFingerprint)) {
            throw new RiskEngineException(RiskErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId=" + requestId + " 已用于不同的请求载荷");
        }
        return Optional.of(existing);
    }

    /**
     * 登记首次成功执行的结果。写锁内串行执行，正常不会出现并发覆盖；
     * 若极端情况下键已存在，必须与当前指纹一致才允许回放。
     */
    public Record store(String requestId, String payloadFingerprint, Object result) {
        Record record = new Record(requestId, payloadFingerprint, result);
        Record previous = records.putIfAbsent(requestId, record);
        return previous != null ? previous : record;
    }

    public int size() {
        return records.size();
    }

    public record Record(String requestId, String payloadFingerprint, Object result) {
    }
}
