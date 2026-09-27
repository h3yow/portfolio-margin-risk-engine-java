# 本地投资组合保证金与风险判定引擎

基于 Spring Boot 的**本地（内存）**投资组合保证金与风险判定服务。支持账户资金/持仓的并发变更，
保证任一次风险计算或强平判定都基于某个**确定时刻的完整一致状态**，且每次结论可追溯到对应的
**状态版本号**。

## 1. 风险模型

简化的组合保证金口径（多空均计提）：

| 指标 | 公式 | 说明 |
| --- | --- | --- |
| 持仓市值 MV | `Σ quantity × price` | 空头 quantity 为负，MV 为负 |
| 权益 Equity | `cash + Σ quantity × price` | 现金 + 全部持仓市值 |
| 维持保证金 MM | `Σ |quantity| × price × m` | 维持保证金率 `m`，默认 0.25，可用 `risk.maintenance-margin-rate` 配置 |
| 可用保证金 Available | `Equity − MM` | 负值表示已无可用保证金 |
| 强平判定 | `Equity < MM` 触发 | **边界**：Equity == MM 不触发 |

不可定义情形（**绝不静默按零或默认值处理**）：

- 某持仓标的**行情缺失** → 报告 `defined=false`，`reasons` 含 `MARKET_DATA_MISSING`，
  `equity / maintenanceMargin / availableMargin` 全部为 `null`，不进行强平判定。
- 行情价格**非正** → `defined=false`，`reasons` 含 `INVALID_PRICE`。
- 无持仓账户不需要任何行情，指标天然有定义（MM=0）。

每个 `RiskReport` 都带：

- `version`：结论所依据的账本全局版本号（可追溯）；
- `evaluatedAtEpochMs`：计算时间；
- `basis`：人类可读的逐项判定依据（每个标的的 qty/price/mv/mmPart、汇总公式与 TRIGGER/SAFE 结论）。

## 2. 并发一致性保证

- **单一读写锁串行化写事务**（`AccountLedger` 使用公平 `ReentrantReadWriteLock`）：
  - 写事务彼此串行、与读互斥；读操作彼此并行。
  - 每次成功提交且**确有状态变更**时全局版本号 +1；纯幂等回放不产生新版本。
- **快照读**：读路径在持锁期间深拷贝出不可变的 `AccountSnapshot / LedgerSnapshot`，
  随后风险计算在锁外进行。计算所见永远是同一版本的完整状态，不可能读到"扣了 A 还没加 B"的
  部分更新；快照之后的并发变更不影响本次结论。
- **失败整体回滚**：写事务入口对全部账户深拷贝备份，事务中任一步骤抛异常（余额不足、持仓不足、
  自转账等）就整体恢复备份并重新抛出，杜绝部分生效。
- **幂等恰好一次**：每个业务请求必须携带 `requestId`。幂等判定与业务变更在**同一个写事务**内完成：
  - 同 requestId + 同载荷指纹 → 首次结果回放（`replayed=true`），不产生任何二次资金/持仓变动；
  - 同 requestId + 不同载荷 → `IDEMPOTENCY_KEY_CONFLICT` 拒绝；
  - 新 requestId → 执行并登记结果。
- **守恒**：资金划转（from 扣 / to 加）与持仓划转（from 减 / to 加）在同一事务内成对反向记账，
  系统内总现金、每标的总持仓量在压力后保持不变（由并发测试断言）。

> 范围说明：本引擎为单机内存实现，"系统"指本账本内全部账户；存款是资金进入系统的唯一入口，
> 持仓的结算建仓在测试中通过账本写事务直接构造。未实现跨进程持久化与结算对接。

## 3. 错误分类

错误码枚举 `RiskErrorCode`，HTTP 层映射为不同状态码：

| code | 触发场景 | HTTP |
| --- | --- | --- |
| `INVALID_ARGUMENT` | requestId/账户/标的为空、请求体非法 | 400 |
| `NEGATIVE_AMOUNT` | 金额/数量为 0 或负（反向操作不得靠负号参数表达） | 400 |
| `INVALID_PRICE` | 行情写入价格为 null/非正 | 400 |
| `INSUFFICIENT_FUNDS` | 划转金额超过转出账户余额（整笔拒绝，不部分生效） | 400 |
| `INSUFFICIENT_POSITION` | 划转数量超过转出账户持仓 | 400 |
| `SAME_ACCOUNT_TRANSFER` | 转出与转入账户相同 | 409 |
| `ACCOUNT_ALREADY_EXISTS` | 重复开户 | 409 |
| `IDEMPOTENCY_KEY_CONFLICT` | 同一 requestId 绑定了不同请求载荷 | 409 |
| `ACCOUNT_NOT_FOUND` | 账户不存在（不静默创建为零余额账户） | 404 |
| `MARKET_DATA_MISSING` | 风险计算时某标的无行情（作为**标记**出现在报告 reasons，不抛异常） | — |
| `RISK_METRIC_UNDEFINED` / `INTERNAL_ERROR` | 保留分类 | 500 |

风险查询本身对"行情缺失/价格非正"**不抛异常**，而是在 `RiskReport` 中返回
`defined=false` + 可区分的 `reasons`，调用方必须据此判断，不能把数值字段当零使用。

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/accounts` | 开户 `{requestId, accountId, initialCash}` |
| POST | `/api/deposits` | 入账 `{requestId, accountId, amount}` |
| POST | `/api/transfers/cash` | 资金原子划转 `{requestId, fromAccountId, toAccountId, amount}` |
| POST | `/api/transfers/positions` | 持仓原子划转 `{requestId, fromAccountId, toAccountId, symbol, quantity}` |
| POST | `/api/quotes` | 更新行情 `{symbol, price}`（非正价格拒绝） |
| GET  | `/api/accounts/{accountId}/risk` | 风险/强平判定（含 version、reasons、basis） |

命令响应：`{version, replayed, summary}`。错误响应：`{code, message}`。

## 5. 本地验证方法

前置：JDK 21+、Maven（支持离线构建）。

```bash
# 编译
mvn -o compile

# 全量测试（19 个：指标边界、命令幂等/拒绝/守恒、并发一致性）
mvn -o test

# 打包并启动
mvn -o package -DskipTests
java -jar target/portfolio-margin-risk-engine-java-1.0-SNAPSHOT.jar --server.port=18080
```

冒烟示例：

```bash
curl -s -X POST localhost:18080/api/accounts -H 'Content-Type: application/json' \
  -d '{"requestId":"a1","accountId":"A","initialCash":"1000"}'
curl -s -X POST localhost:18080/api/quotes -H 'Content-Type: application/json' \
  -d '{"symbol":"AAA","price":"100"}'
curl -s localhost:18080/api/accounts/A/risk
```

## 6. 测试覆盖与日志

- `RiskCalculatorTest`（7 个）：SAFE / 权益等于 MM 的**边界不触发** / 触发强平 /
  行情缺失不可定义 / 价格非正可区分标记 / 空仓账户 / 空头市值为负但正常计提 MM。
- `AccountCommandServiceTest`（8 个）：重复提交回放且仅生效一次、同键不同载荷冲突、
  资金不足拒绝且不推进版本、持仓不足拒绝、自转账拒绝、非正金额拒绝、未知账户不静默创建、
  资金/持仓总量守恒。
- `ConcurrentConsistencyTest`（3 个）：
  - 同一请求 16 线程并发提交，恰有 1 次生效、其余全部回放；
  - 8 线程 × 200 轮随机资金/持仓划转（含超额与拒绝），压力结束后断言总现金=4000、
    SYM 总持仓=400 守恒，且拒绝原因都属于可区分枚举；
  - 读写并发下每次风险结论绑定确定版本、指标关系不变量成立。

所有单测均通过 SLF4J 打印**输入参数、状态版本 version 与判定依据 basis**（见 surefire 输出或
`target/surefire-reports/`）。

## 7. 主要代码结构

```
domain/      RiskErrorCode, RiskEngineException, AccountSnapshot, Position, RiskReport
ledger/      AccountLedger（读写锁/版本/快照/回滚）, AccountState, IdempotencyTable
market/      MarketDataProvider, InMemoryMarketDataProvider, Quote
risk/        RiskCalculator（纯计算：权益/MM/可用保证金/强平）
service/     AccountCommandService（幂等+守恒的命令）, RiskQueryService（快照查询）, EngineConfig
web/         AccountController, GlobalExceptionHandler, web/dto
```
