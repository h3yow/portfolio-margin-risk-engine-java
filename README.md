# 本地投资组合保证金与风险判定引擎

基于 Java 21 + Spring Boot 3.4 的本地内存实现，支持账户资金/持仓的并发变更、
风险指标计算与强平触发判定。所有结论基于**确定版本的完整一致状态**，可追溯、可解释，
异常情形以**可区分的错误码**显式失败，绝不静默按零或默认值处理。

---

## 1. 风险模型

保证金账户（多头模型，允许保证金贷款）：

| 指标 | 定义 |
| --- | --- |
| 现金 `cash` | 可正可负；负值代表向券商借入的保证金贷款。入金增加、出金减少；买入按成交额扣减、卖出按成交额增加 |
| 持仓 `Position(symbol, qty, avgCost)` | 仅多头，`qty > 0`；买入按加权平均成本更新 `avgCost`，卖出不改变成本价，数量减为 0 时移除 |
| 权益 `equity` | `cash + Σ(qty × price)` |
| 维持保证金 `MM` | `Σ(持仓市值 × marginRate(symbol))`，保证金率按标的配置，区间 `(0, 1]` |
| 可用保证金 `availableMargin` | `equity - MM` |
| 强平判定 | `availableMargin < 0`（**严格小于**；等于 0 为边界，不触发）→ `status=LIQUIDATION, liquidation=true` |

**开仓（BUY）初始保证金校验**：成交后按成本口径预估
`proFormaAvailable = 预估权益 − Σ(各持仓成本 × marginRate)`，不允许为负，
否则以 `INSUFFICIENT_FUNDS` 拒绝，整笔交易不生效。该校验保证新增贷款不超过保证金率允许的杠杆上限。

**出金（WITHDRAW）**：只允许动用自有正现金，`cash − amount < 0` 即拒绝（不允许用贷款出金）。

### 无法定义即失败（不做零值兜底）

风险计算是纯函数：任一输入无法定义，则整个指标不定义并显式报错——

- 持仓标的在行情快照中缺失 → `MARKET_DATA_MISSING`（不会按 0 估值）；
- 行情价格为 null / ≤ 0 → `INVALID_PRICE`；
- 标的未配置维持保证金率 → `MARGIN_RATE_MISSING`（不会默认率 0）；
- 买入数量/金额为非正数、symbol 为空等 → `INVALID_ARGUMENT`。

每次 `RiskDecision` 都带：账户版本、行情版本、逐项估值（数量/价格/市值/保证金率/维持保证金）
与人类可读的 `reasons` 判定依据。

---

## 2. 并发一致性保证

### 2.1 不可变快照 + 版本号 + CAS

- 账户状态是不可变记录 `AccountSnapshot(accountId, version, updatedAtMillis, cash, positions)`，
  持仓 Map 在构造时做防御性不可变拷贝。快照一旦发布，任何线程读到的内容永不改变。
- 所有变更经 `AccountRepository.mutate` 的 CAS 循环：变换函数基于某完整快照
  **整体**产出下一完整快照（`version+1`），`ConcurrentHashMap.replace(key, expected, next)`
  成功才生效；失败说明已有并发变更先落地，基于最新快照整体重试。

由此保证：

1. **读不到部分更新**：读侧只看到旧版本或新版本，永远看不到"现金改了、持仓没改"的中间态；
2. **不丢更新（lost update）**：CAS 排除两笔变更相互覆盖；
3. **失败不产生部分生效**：业务校验（余额不足、持仓不足、版本冲突）在迁移函数内抛异常，
   快照引用未被替换，账户停留在原版本；
4. **版本即因果链**：每次成功变更版本 +1，结论中的 `accountVersion` 精确指向所依据的状态。

行情同理：全部报价在一张不可变 `MarketSnapshot(version, ...)` 中，CAS 原子替换。

> 风险查询同时读取账户快照与行情快照，二者各自完整一致；结论同时记录
> `accountVersion` 与 `quoteVersion`，可分别追溯。

### 2.2 幂等：同一业务请求只生效一次

业务请求必须携带 `requestId`，以 `(accountId, requestId)` 为幂等键。
服务内用 `ConcurrentHashMap<幂等键, CompletableFuture<OperationResult>>` 处理：

- `putIfAbsent` 抢占成功的首个请求执行 CAS 迁移并落地结果；
- 并发的重复提交会等待首个请求完成并复用其结论，返回 `duplicate=true`、
  相同的 `accountVersion`，**不会二次扣款/入账/调整持仓**；
- 即使重复请求内容不同（金额/方向不同），同一 requestId 仍按同一笔业务处理；
- 只有**成功生效**的请求永久占用幂等键；业务失败（余额不足、版本冲突等）会释放占用，
  允许调用方修正后用同一 requestId 重试——失败的首次请求本就没有改变状态，重试不会双重生效；
  成功之后的任何重放则永远返回首次结论。

### 2.3 乐观并发：expectedVersion

请求可携带 `expectedVersion`：迁移前校验当前账户版本必须等于它，否则返回
`VERSION_CONFLICT`（HTTP 409），调用方应基于最新状态重读后再提交。
不传则不做乐观检查，由引擎 CAS 自动串行化。

### 2.4 守恒恒等式（压测校验）

- 现金：`期末现金 = 初始现金 + Σ入金 − Σ出金 − Σ买入成交额 + Σ卖出成交额`
- 持仓：`期末某标的数量 = 初始数量 + Σ买入量 − Σ卖出量`
- 版本：`期末 version = 初始 version + 成功变更笔数`

---

## 3. 错误分类

| ErrorCode | 含义 | HTTP |
| --- | --- | --- |
| `ACCOUNT_NOT_FOUND` | 账户不存在 | 404 |
| `ACCOUNT_ALREADY_EXISTS` | 重复开户 | 409 |
| `VERSION_CONFLICT` | expectedVersion 与当前版本不符 | 409 |
| `INVALID_ARGUMENT` | 参数非法（空值、非正金额/数量、枚举错误等） | 400 |
| `INVALID_PRICE` | 行情价/成交价为 null 或非正 | 400 |
| `INSUFFICIENT_FUNDS` | 出金超额 / 买入不满足初始保证金 | 422 |
| `INSUFFICIENT_POSITION` | 卖出超过持仓数量 | 422 |
| `MARKET_DATA_MISSING` | 持仓标的缺少行情，指标无法定义 | 503 |
| `MARGIN_RATE_MISSING` | 标的未配置维持保证金率 | 503 |

响应体形如：`{"code":"INSUFFICIENT_FUNDS","message":"...当前现金=..，请求金额=..，version=.."}`。

---

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/accounts` | 开户，body `{"accountId":"..."}` |
| GET  | `/api/accounts/{id}` | 查当前完整快照（含版本） |
| GET  | `/api/accounts/{id}/risk` | 风险指标 + 强平判定 + 判定依据 |
| POST | `/api/transfers` | 资金划转：`requestId,type=DEPOSIT|WITHDRAW,amount,expectedVersion?` |
| POST | `/api/positions/adjust` | 成交：`requestId,symbol,side=BUY|SELL,qty,price,expectedVersion?` |
| PUT  | `/api/market/quotes` | 写入行情：`symbol,price`（价格非正 400） |
| PUT  | `/api/margin-rates` | 配置维持保证金率：`symbol,rate∈(0,1]` |

返回示例（风险查询）：

```json
{
  "accountId": "WEB-1",
  "accountVersion": 2,
  "quoteVersion": 2,
  "status": "LIQUIDATION",
  "liquidation": true,
  "reasons": [
    "现金 -800 + 持仓总市值 900 = 权益 100；维持保证金合计 180.0；可用保证金 = 权益 - 维持保证金 = -80.0",
    "标的 AAA：数量 100，价格 9（行情版本 2），市值 900，维持保证金率 0.2，维持保证金 180.0",
    "可用保证金 -80.0 < 0，权益不足以覆盖维持保证金，触发强平判定"
  ],
  "metrics": { "equity": 100, "maintenanceMargin": 180.0, "availableMargin": -80.0, "...": "..." }
}
```

操作返回：`{"requestId":"...","applied":true,"duplicate":false,"accountVersion":2,"message":"..."}`。

---

## 5. 本地验证

环境：JDK 21、Maven 3.6+。

```bash
# 全量测试（18 个用例：指标边界 / 并发一致性 / 重复提交 / HTTP 端到端）
mvn test

# 只跑并发一致性与守恒压测（8 线程 × 200 轮）
mvn test -Dtest=AccountConsistencyTest

# 只跑风险指标边界与错误分类
mvn test -Dtest=RiskCalculatorTest

# 端到端 HTTP
mvn test -Dtest=RiskControllerIntegrationTest

# 启动服务（随机端口由 server.port=0 指定，见 application.properties）
mvn spring-boot:run
```

测试日志（控制台 / `target/surefire-reports/`）打印每个用例的**输入、状态版本与判定依据**，
压测结束打印 `accountVersion / 成功数 / 拒绝数 / 期望值 vs 实际值` 并断言守恒。
为避免压测中逐请求日志刷屏，服务层在测试期为 WARN 级别（`src/test/resources/logback-test.xml`），
测试类自身仍以 INFO 打印输入、版本、依据与守恒汇总。

### 手工冒烟

```bash
curl -s -XPOST localhost:$PORT/api/accounts -H 'Content-Type: application/json' -d '{"accountId":"demo"}'
curl -s -XPUT  localhost:$PORT/api/margin-rates -H 'Content-Type: application/json' -d '{"symbol":"AAA","rate":0.2}'
curl -s -XPUT  localhost:$PORT/api/market/quotes  -H 'Content-Type: application/json' -d '{"symbol":"AAA","price":10}'
curl -s -XPOST localhost:$PORT/api/transfers -H 'Content-Type: application/json' \
  -d '{"accountId":"demo","requestId":"d1","type":"DEPOSIT","amount":200}'
curl -s -XPOST localhost:$PORT/api/positions/adjust -H 'Content-Type: application/json' \
  -d '{"accountId":"demo","requestId":"b1","symbol":"AAA","side":"BUY","qty":100,"price":10}'
curl -s localhost:$PORT/api/accounts/demo/risk
# 再用同一 requestId 重放一次，第二次返回 duplicate=true 且 accountVersion 不变
```

---

## 6. 范围与说明（bootstrap）

- 纯内存存储、单进程：重启数据丢失；幂等表为进程内 Map，生产实现应替换为带 TTL 的持久存储。
- 多头模型（不允许负持仓）；维持保证金率按标的静态配置。
- 开仓初始保证金校验与维持保证金率同口径、按成本估值，不依赖行情快照（确定且无循环依赖）；
  风险查询按最新行情重估，二者分别服务于"成交时能不能做"与"现在该不该强平"。
- 金额使用 `BigDecimal`，除法仅用于加权平均成本（整除语义来自 `qty` 缩放，如需更高精度可指定 scale/舍入）。
