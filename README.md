# LeaseFlow

LeaseFlow 是一个面向融资租赁场景的 Spring Boot 后端项目，用于承载租赁资产、合同租金及其相关业务数据。当前已实现业务功能：创建融资租赁项目，按等额本金或等额本息方式生成月度租金计划，按合同、期次登记租金回款，支持按业务日期扫描逾期租金、生成和管理催收任务，并为租赁物维护版本化的残值评估记录。

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

### 执行逾期扫描

`POST /api/collection-tasks/scan`

按业务日期对全部合同的租金期次执行逾期扫描，请求体：

```json
{
  "businessDate": "2025-04-15"
}
```

扫描规则：

- 到期日**早于**业务日期（严格小于，到期日当天不算逾期）且仍有未收金额（应还总额 − 累计已收 > 0）的期次，对应一条催收任务；
- 逾期天数 = 业务日期 − 到期日的自然日差（`ChronoUnit.DAYS`）；
- 每个合同期次至多一条催收任务：无任务则新建（计入 `createdCount`），已有 OPEN 任务则刷新逾期天数与未收金额，仅在二者实际变化时计入 `updatedCount`；
- 部分回款的期次按剩余未收金额继续催收（仍为 `OPEN`）；
- 已足额回款的期次不生成任务；已有 OPEN 任务在足额回款后的下一次扫描中关闭（`CLOSED`，计入 `closedCount`，未收金额置为 0），已关闭任务不重复关闭、不再复活。

成功返回 `200`，响应包含：

- `businessDate`：本次扫描的业务日期；
- `createdCount`：本次新建任务数；
- `updatedCount`：本次刷新（逾期天数或未收金额发生变化）的 OPEN 任务数；
- `closedCount`：本次由 OPEN 变为 CLOSED 的任务数；
- `tasks`：本次扫描涉及的逾期期次对应任务（含新建、刷新、关闭及未变更的已有任务），按到期日、合同编号、期次升序排列。

任务字段：`contractNo`（合同编号）、`periodNo`（租金期次）、`dueDate`（到期日）、`overdueDays`（逾期天数）、`outstandingAmount`（未收金额）、`status`（状态，`OPEN` 催收中 / `CLOSED` 已关闭）。

重复扫描是幂等的：同一业务日期重复扫描不产生重复数据，各项计数为 0。并发扫描通过对候选期次加行级悲观锁（`SELECT … FOR UPDATE`，按到期日、合同、期次排序加锁）串行化，配合 `collection_task.schedule_item_id` 唯一约束双重保证每个期次只有一条任务。扫描结果与任务变更在同一数据库事务内完成，任一步骤失败时整笔事务回滚，不留下部分新增或更新。

### 查询催收任务

`GET /api/collection-tasks`

可选查询参数：

- `contractNo`：按合同编号筛选；
- `status`：按状态筛选，取值 `OPEN` 或 `CLOSED`，传入其他值返回 `400` 并提示允许值。

两个参数可任意组合；不传则返回全部催收任务。结果始终按到期日、合同编号、期次升序稳定排序。无匹配时返回空数组。

### 登记残值评估

`POST /api/assets/{assetCode}/assessments`

为租赁物追加一条残值评估版本，请求体：

```json
{
  "assessmentNo": "EV-20250601-001",
  "assessmentDate": "2025-06-01",
  "assessedValue": 120000.00,
  "appraiser": "中联评估",
  "baseVersion": 0
}
```

- `assessmentNo`：全局唯一的评估编号；
- `assessmentDate`：评估日期，不得早于合同起租日，且必须晚于该租赁物当前最新评估记录的日期；
- `assessedValue`：评估价值，必须处于 0 到资产原值之间（含边界），最多保留 2 位小数；
- `appraiser`：评估机构；
- `baseVersion`：客户端看到的当前最新版本号（无任何评估记录时为 0）。

每项租赁物维护一条不可变的评估版本链：版本号从 1 开始按资产严格递增，历史版本一旦写入不得覆盖或删除。仅当 `baseVersion` 与当前最新版本一致且评估日期晚于最新记录时才追加成功，新版本号 = 当前版本 + 1。

成功返回 `201`，响应包含 `assessmentNo`、`assetCode`、`version`、`assessmentDate`、`assessedValue`、`appraiser`、`impairmentAmount`（减值金额 = 资产原值 − 评估价值，2 位小数 HALF_UP）、`residualRate`（残值率 = 评估价值 ÷ 资产原值，4 位小数 HALF_UP）及 `latest`（是否为最新版本）。

幂等与冲突：

- 相同 `assessmentNo` 且资产、日期、价值、机构完全一致的重复提交返回 `200` 及首次登记结果，不新增版本；
- `assessmentNo` 复用但内容不一致（含用于其他资产）返回 `409`（`DUPLICATE_RESOURCE`）；
- `baseVersion` 与当前最新版本不一致（过期或超前）返回 `409`（`VERSION_CONFLICT`）；
- 评估日期早于起租日、不晚于最新评估日期，或评估价值超出 0 到原值范围，返回 `400`；
- 租赁物不存在返回 `404`。

同一租赁物的并发登记通过对租赁物行加悲观写锁串行化，配合 `residual_assessment` 表 `(asset_id, version)` 与 `assessment_no` 唯一约束兜底，保证基于同一当前版本的并发请求只接受一个。登记在单一事务内完成，失败时整体回滚，不留下跳号版本或不完整记录。

### 查询评估历史与当前版本

`GET /api/assets/{assetCode}/assessments`

返回该租赁物的全部评估版本，按版本号升序稳定排序，每条记录带 `latest` 标记（仅最新版本为 `true`）；无评估记录时返回空数组，租赁物不存在返回 `404`。

`GET /api/assets/{assetCode}/assessments/current`

返回该租赁物当前最新评估版本；无评估记录或租赁物不存在返回 `404`。

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

### 残值评估

- 残值评估持久化在 `residual_assessment` 表：`assessment_no` 全局唯一，`(asset_id, version)` 唯一约束保证每项租赁物的版本号从 1 开始严格递增、不跳号；记录只新增、不更新、不删除，构成不可变版本链；
- 登记时对租赁物行加悲观写锁（`SELECT … FOR UPDATE`），串行化同一租赁物的并发评估，唯一约束兜底，确保基于同一当前版本的并发请求只有一个成功，其余返回 `409`；
- 评估编号幂等：编号与资产、日期、价值、机构完全一致的重复提交返回首次结果；编号复用但内容不一致返回 `409`；
- 减值金额 = 资产原值 − 评估价值（2 位小数，HALF_UP）；残值率 = 评估价值 ÷ 资产原值（4 位小数，HALF_UP），均随版本持久化并在查询时返回；
- 登记在单一事务内完成版本校验、业务校验与写入，任一步骤失败整体回滚，不留下跳号版本或不完整记录。

### 催收任务

- 催收任务持久化在 `collection_task` 表：外键 `schedule_item_id` 指向租金期次并设有唯一约束（每个期次至多一条任务），`status` 列以枚举字符串保存 `OPEN`/`CLOSED`；
- 逾期扫描在单个事务内对全部候选期次（`due_date < 业务日期`）加行级悲观写锁并按到期日、合同编号、期次排序，再逐条新建、刷新或关闭任务，最后统一落库；悲观锁串行化并发扫描，唯一约束兜底，重复扫描幂等；
- 任务状态在足额回款后的下一次扫描中由 `OPEN` 变为 `CLOSED`；CLOSED 为终态，后续扫描不重复关闭、不复活；
- 任务表不冗余合同编号、期次与到期日，查询时通过关联的租金期次与合同实时取得，避免数据不一致；
- 扫描事务中任一步骤失败则整体回滚，不留下部分任务或部分状态更新。

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

执行逾期扫描（业务日期 2025-04-15）：

    curl -X POST http://localhost:8080/api/collection-tasks/scan \
      -H "Content-Type: application/json" \
      -d '{"businessDate":"2025-04-15"}'

查询催收任务（可按合同编号、状态筛选）：

    curl "http://localhost:8080/api/collection-tasks?contractNo=HT-001&status=OPEN"

登记残值评估（baseVersion 为客户端看到的最新版本，首次评估为 0）：

    curl -X POST http://localhost:8080/api/assets/ASSET-001/assessments \
      -H "Content-Type: application/json" \
      -d '{"assessmentNo":"EV-20250601-001","assessmentDate":"2025-06-01","assessedValue":120000.00,"appraiser":"中联评估","baseVersion":0}'

查询评估历史与当前版本：

    curl http://localhost:8080/api/assets/ASSET-001/assessments
    curl http://localhost:8080/api/assets/ASSET-001/assessments/current
