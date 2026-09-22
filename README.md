# LeaseFlow

LeaseFlow 是一个面向融资租赁场景的 Spring Boot 后端项目，用于承载租赁资产、合同租金及其相关业务数据。当前已实现业务功能：创建融资租赁项目，按等额本金或等额本息方式生成月度租金计划，按合同、期次登记租金回款，并支持按业务日期扫描逾期租金、生成与管理催收任务。

## 环境

- Java 21
- Maven Wrapper
- Spring Boot 4.1.1
- H2 内存数据库

## 接口

### 创建租赁项目

`POST /api/leases`

请求体：

```json
{
  "assetCode": "ASSET-001",
  "assetName": "数控机床",
  "category": "生产设备",
  "originalValue": 150000.00,
  "contractNo": "HT-001",
  "startDate": "2025-01-01",
  "firstPaymentDate": "2025-02-01",
  "financingAmount": 120000.00,
  "nominalAnnualRate": 0.12,
  "termMonths": 12,
  "repaymentMethod": "EQUAL_PAYMENT"
}
```

`repaymentMethod` 可选，取值 `EQUAL_PRINCIPAL`（等额本金，缺省默认值）或 `EQUAL_PAYMENT`（等额本息）；传入其他值返回 `400`，错误响应的 `errors` 列表中指出字段 `repaymentMethod` 及原因。

成功返回 `201`，响应包含租赁物、合同（含实际采用的 `repaymentMethod`）、完整租金计划（每期含期次、应还日、期初本金、应还本金、应还利息、应还总额、期末本金）及汇总金额（本金合计、利息合计、租金合计）。

### 查询租赁项目

`GET /api/leases/{contractNo}`

返回租赁项目详情（含持久化的 `repaymentMethod`）及按期次升序排列的租金计划，计划与汇总与创建结果一致；合同不存在返回 `404`。

每期计划除原有金额字段外，还包含：

- `paidAmount`：该期累计已收金额；
- `outstandingAmount`：该期未收金额（应还总额 − 已收金额）；
- `paymentStatus`：回款状态，`UNPAID`（无回款）、`PARTIAL`（已收小于应还）或 `PAID`（已收等于应还）。

汇总在原有本金、利息、租金合计之外，增加 `totalPaid`（累计已收合计）与 `totalOutstanding`（未收合计）。原有计划金额（期初本金、应还本金、应还利息、应还总额、期末本金）及 `repaymentMethod` 保持不变。

### 登记租金回款

`POST /api/leases/{contractNo}/schedule/{periodNo}/payments`

按合同编号和租金期次登记一笔回款，请求体：

```json
{
  "paymentNo": "PAY-20250205-001",
  "amount": 5000.00,
  "paymentDate": "2025-02-05"
}
```

- `paymentNo`：全局唯一的回款流水号；
- `amount`：回款金额，必须大于 0，最多保留 2 位小数，且与该期历史回款累计后不得超过该期应还总额；
- `paymentDate`：回款日期。

成功返回 `201`，响应包含回款信息（`payment`：流水号、期次、金额、日期）以及该期结果（`period`：期次、`totalDue` 应还总额、`paidAmount` 累计已收、`outstandingAmount` 未收金额、`paymentStatus` 回款状态）。

同一期支持分多次回款：累计已收小于应还总额时为 `PARTIAL`，等于应还总额时为 `PAID`，没有回款时为 `UNPAID`。

错误情况：

- 合同不存在，或该合同下期次不存在，返回 `404`；
- 回款流水号重复（即使发生在不同合同或不同期次之间）返回 `409`；
- 回款金额小于等于 0、格式非法，或导致累计回款超过该期应还总额，返回 `400`；
- 所有错误均沿用统一错误响应结构。

回款记录与租金期次已收金额在同一数据库事务内持久化；登记失败（含流水号冲突、超额回款等）时整笔事务回滚，不留下回款记录，也不改变已收金额。

### 逾期扫描

`POST /api/collection-tasks/scan?businessDate=2025-04-10`

按业务日期执行一次全量逾期扫描：

- 租金期次到期日严格早于业务日期（到期日当天不算逾期）且仍有未收金额（应还总额 − 累计已收 > 0）时，为该合同期次生成一条催收任务；
- 每个合同期次最多一条催收任务。重复扫描或并发扫描不会产生重复数据，再次扫描会按业务日期刷新逾期天数、按最新回款情况刷新未收金额；
- 部分回款后按剩余未收金额催收；
- 已有 OPEN 任务的期次足额回款后，下一次扫描将任务置为 `CLOSED`（已足额回款的期次不会新生成任务，CLOSED 任务保持不变）；
- 逾期天数 = 业务日期 − 到期日的自然日差。

成功返回 `200`：

```json
{
  "businessDate": "2025-04-10",
  "createdCount": 2,
  "updatedCount": 1,
  "closedCount": 1
}
```

计数口径：

- `createdCount`：本次扫描新生成的催收任务数；
- `updatedCount`：本次扫描中逾期天数或未收金额实际发生变化的已存在 OPEN 任务数（同一业务日期重复扫描为 0）；
- `closedCount`：本次扫描由 OPEN 变为 CLOSED 的任务数。

扫描对全部到期期次加行级悲观锁并按期次主键升序处理，扫描结果与任务变更在同一数据库事务内持久化；扫描失败时整笔事务回滚，不留下部分任务数据。

缺少 `businessDate` 或日期格式不是 `yyyy-MM-dd` 时返回 `400`。

### 查询催收任务

`GET /api/collection-tasks`

可选查询参数：

- `contractNo`：按合同编号精确筛选；
- `status`：按状态筛选，取值 `OPEN`、`CLOSED`，其他值返回 `400`。

两个参数可任意组合（均不传则返回全部任务）。结果按到期日、合同编号、租金期次升序稳定排序。每条任务包含合同编号、租金期次、到期日、逾期天数、未收金额和状态：

```json
[
  {
    "contractNo": "HT-001",
    "periodNo": 1,
    "dueDate": "2025-02-01",
    "overdueDays": 68,
    "outstandingAmount": 6200.00,
    "status": "OPEN"
  }
]
```

### 校验与错误响应

- 租赁物编码、合同编号分别唯一，重复返回 `409`。
- 原值、融资金额必须大于 0，且融资金额不得超过原值；名义年利率取值 0 到 1（允许为 0）；期数 1 到 120；首期应还日不得早于起租日。参数或业务校验失败返回 `400`。
- 错误响应统一结构：`code`（错误码）、`message`（消息）、`timestamp`（时间），字段校验失败时附带 `errors`（字段及原因）列表。

## 租金计算规则

### 公共规则

- 月利率 = 名义年利率 ÷ 12；每期利息 = 期初剩余本金 × 月利率，保留 2 位小数（HALF_UP）。
- 每期应还日以首期应还日为锚点逐月增加（`firstPaymentDate.plusMonths(n)`），避免从上一期递推造成月末日期漂移。例如首期为 1 月 31 日，后续依次为 2 月 28/29 日、3 月 31 日等合法日期。
- 所有金额与利率计算均使用 `BigDecimal`，不使用 `double`/`float`。
- 租赁物、合同、租金计划通过 JPA 在同一事务中持久化，数据库层设有唯一约束，失败时不留部分数据。
- 合同持久化实际采用的还款方式（`repayment_method` 列，枚举字符串）。
- 租金期次持久化累计已收金额（`paid_amount` 列，初始为 0）；回款记录独立持久化（`rent_payment` 表），流水号 `payment_no` 全局唯一，登记回款时对目标期次加行级悲观锁并在同一事务内写入回款记录、累加已收金额。
- 回款状态不落库，按 `total_due` 与 `paid_amount` 实时推导：已收为 0 → `UNPAID`，已收小于应还 → `PARTIAL`，已收等于应还 → `PAID`。
- 催收任务持久化在 `collection_task` 表，通过 `schedule_item_id` 外键唯一约束保证每个合同期次最多一条任务；冗余存储合同编号、期次、到期日以支持稳定查询排序。任务状态为 `OPEN` / `CLOSED`（枚举字符串落库）。
- 逾期扫描对全部到期期次加行级悲观写锁并按期次主键升序处理，串行化重复与并发扫描，配合期次唯一约束杜绝重复任务；任务新增、刷新、关闭与计数在同一事务内完成，失败整体回滚。

### 等额本金（EQUAL_PRINCIPAL，默认）

- 每月应还本金 = 融资金额 ÷ 期数，保留 2 位小数（HALF_UP）；最后一期承担累计舍入差额，保证本金合计严格等于融资金额，期末本金为 0。

### 等额本息（EQUAL_PAYMENT）

- 月供按标准年金公式计算：月供 = 融资金额 × 月利率 × (1 + 月利率)^期数 ÷ ((1 + 月利率)^期数 − 1)，保留 2 位小数（HALF_UP）；月利率 = 名义年利率 ÷ 12。
- 零利率时月供 = 融资金额 ÷ 期数（平均分摊，保留 2 位小数，HALF_UP）。
- 每期应还本金 = 月供 − 当期利息；最后一期以剩余本金作为应还本金、本金加当期利息作为应还总额，用于吸收累计舍入差额。
- 计划保证：本金合计严格等于融资金额、最后一期期末本金为 0、汇总与明细求和一致、所有金额非负。

## 常用命令

运行测试：

    ./mvnw test

启动应用：

    ./mvnw spring-boot:run

启动后可通过 curl 调用接口，例如：

    curl -X POST http://localhost:8080/api/leases \
      -H "Content-Type: application/json" \
      -d '{"assetCode":"ASSET-001","assetName":"数控机床","category":"生产设备","originalValue":150000.00,"contractNo":"HT-001","startDate":"2025-01-01","firstPaymentDate":"2025-02-01","financingAmount":120000.00,"nominalAnnualRate":0.12,"termMonths":12}'

    curl http://localhost:8080/api/leases/HT-001

登记第 1 期回款：

    curl -X POST http://localhost:8080/api/leases/HT-001/schedule/1/payments \
      -H "Content-Type: application/json" \
      -d '{"paymentNo":"PAY-20250205-001","amount":5000.00,"paymentDate":"2025-02-05"}'

按业务日期执行逾期扫描：

    curl -X POST "http://localhost:8080/api/collection-tasks/scan?businessDate=2025-04-10"

查询催收任务（可按合同编号、状态筛选）：

    curl "http://localhost:8080/api/collection-tasks?contractNo=HT-001&status=OPEN"
