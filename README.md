# LeaseFlow

LeaseFlow 是一个面向融资租赁场景的 Spring Boot 后端项目，用于承载租赁资产、合同租金及其相关业务数据。当前已实现业务功能：创建融资租赁项目，按等额本金或等额本息方式生成月度租金计划，按合同、期次登记租金回款，并支持按业务日期扫描逾期租金、生成和管理催收任务；此外为每项租赁物维护不可变的残值评估版本链，支持评估登记（乐观版本控制）、评估历史与当前版本查询；并在租赁结束后提供残值结算，把最新评估版本、实际处置收入与合同计算规则一次性冻结为最终金额及明细，结算后只允许通过只追加、不覆盖原始依据的更正流程修正。

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

### 登记租赁物残值评估

`POST /api/assets/{assetCode}/valuations`

为指定租赁物在其不可变的评估版本链上追加一个新版本，请求体：

```json
{
  "valuationNo": "VAL-20250301-001",
  "expectedVersion": 0,
  "valuationDate": "2025-03-01",
  "residualValue": 120000.00,
  "institution": "中评评估机构"
}
```

- `valuationNo`：全局唯一的评估编号；
- `expectedVersion`：客户端看到的最新版本号，首次评估传 `0`，之后从历史/当前版本查询结果取得；
- `valuationDate`：评估日期，不得早于合同起租日，且必须严格晚于该资产当前最新评估日期；
- `residualValue`：评估价值，必须大于等于 0 且不超过资产原值，最多保留 2 位小数；
- `institution`：评估机构。

成功追加返回 `201`，响应为新版本视图：`valuationNo`、`assetCode`、`versionNo`（从 1 起连续递增）、`valuationDate`、`residualValue`、`impairmentAmount`（减值金额 = 资产原值 − 评估价值）、`residualRate`（残值率 = 评估价值 ÷ 资产原值）、`institution`、`latest`（是否最新版本，追加成功时为 `true`）。

版本链规则：

- 登记采用乐观版本控制：只有 `expectedVersion` 等于服务端当前最新版本号时才能追加；版本不匹配（过期或跳号）返回 `409`（错误码 `VERSION_CONFLICT`），客户端需重新查询最新版本后再提交；
- 评估日期必须**严格晚于**当前最新评估日期（同日或更早返回 `409`），因此版本链同时按版本号和评估日期单调递增；
- 历史版本不可变，不提供任何覆盖或删除入口。

编号唯一与幂等：

- `valuationNo` 全局唯一（数据库唯一约束 `uk_asset_valuation_no`）；
- 相同编号连同相同资产、日期、价值、机构重复提交时视为重试，幂等返回**首次登记结果**（HTTP `200`，版本号与首次一致），不产生新版本；
- 编号复用但任一内容（资产、日期、价值、机构）不一致时返回 `409`（错误码 `DUPLICATE_RESOURCE`）。

并发控制：登记事务先对租赁物行加悲观写锁（`SELECT … FOR UPDATE`）串行化同一资产的并发请求，再读取最新版本执行版本匹配与日期校验；配合 `(asset_id, version_no)` 唯一约束兜底，保证同一资产的并发评估只有一个基于当前版本的请求成功，其余得到 `409`。任何校验或写入失败时整笔事务回滚，版本号由“当前版本 + 1”在事务内分配，不留下跳号版本或不完整记录。

错误情况：资产不存在返回 `404`；评估日期早于起租日、评估价值超出 0 到原值范围等业务校验失败返回 `400`；字段格式校验失败返回 `400` 并在 `errors` 中列出字段原因。

### 查询资产评估历史

`GET /api/assets/{assetCode}/valuations`

返回 `assetCode`、`latestVersion`（当前最新版本号，无评估时为 `0`）与 `valuations` 版本列表。列表按版本号升序稳定排列，每个版本均带 `latest` 标记，仅最新版本为 `true`。资产不存在返回 `404`；资产存在但尚无评估时返回 `200` 与空数组。

### 查询当前评估版本

`GET /api/assets/{assetCode}/valuations/current`

返回最新版本视图（`latest` 恒为 `true`）。资产不存在返回 `404`；资产存在但尚无评估返回 `404`。

> 资产完成残值结算（见下节）后进入 `SETTLED` 状态，不再接受**普通评估**：登记新评估返回 `400`，提示改走结算更正流程。历史评估编号的相同内容重试仍按既有幂等规则返回首次结果，评估版本链本身仍不可变。

### 确认残值结算

`POST /api/assets/{assetCode}/residual-settlements`

在租赁结束后，基于**最新评估版本**和**实际处置收入**确认残值结算。结算不是把评估状态改成已完成，而是在同一事务内把计算依据冻结、算出最终金额与明细，并把资产置为 `SETTLED`。请求体：

```json
{
  "settlementNo": "STL-20250401-001",
  "expectedVersion": 2,
  "disposalIncome": 48000.00,
  "disposalDate": "2025-04-01",
  "settlementDate": "2025-04-02",
  "settlementRuleCode": "RESIDUAL_VS_DISPOSAL"
}
```

- `settlementNo`：全局唯一的结算编号；
- `expectedVersion`：客户端看到的最新评估版本号（取自评估历史/当前版本查询）；
- `disposalIncome`：实际处置收入，必须大于等于 0，最多保留 2 位小数；
- `disposalDate`：处置日期，不得早于结算所依据评估的评估日期；
- `settlementDate`：结算日期，可选，不传取确认当日，且不得早于处置日期；
- `settlementRuleCode`：本次采用的结算计算规则代码，随合同规则一并冻结。

成功返回 `201`，响应为结算完整视图，包含三类信息：

- **冻结的评估依据**（`valuationBasis`）：所采用最新评估版本的版本号、评估编号、评估日期、评估残值、减值金额、残值率、评估机构，以确认时刻的快照保存，事后评估链变化不影响本结算；
- **冻结的合同计算规则**（`contractBasis`）：合同编号、起租日、资产原值、融资金额、名义年利率、还款方式（`repaymentMethod`）及结算规则代码 `settlementRuleCode`；
- **实际处置与最终结果**：`disposalIncome`、`disposalDate`、`settlementDate`，以及最终结算差额与明细。

金额口径（均 2 位小数 HALF_UP，`BigDecimal`）：

- 结算差额 `settlementDiff` = 冻结的评估残值 − 实际处置收入；
- 差额方向 `diffDirection`：`PAYABLE`（差额 > 0，承租人应补，`payableAmount` 为差额、`refundableAmount` 为 0）、`REFUNDABLE`（差额 < 0，应退承租人，`refundableAmount` 为差额绝对值）、`EVEN`（差额 = 0，结清，两者均为 0）。

明细 `items` 按 `lineNo` 有序展开计算过程，原始结算固定三行：

1. `RESIDUAL_VALUE`（`ADD`）：评估残值，带符号金额为正；
2. `DISPOSAL_INCOME`（`DEDUCT`）：实际处置收入，带符号金额为负；
3. `SETTLEMENT_DIFF`（`RESULT`）：结算差额，带符号金额即差额本身；前两行带符号金额之和恰等于该结果。

**冻结与并发互斥**：确认事务先对租赁物行加悲观写锁（与评估登记共用同一把锁 `SELECT … FOR UPDATE`），再读取最新评估版本并比对 `expectedVersion`。因此“登记新评估”与“确认结算”并发时，二者在锁上串行，叠加版本比对，**只有一方能成功**：

- 结算先拿到锁：结算成功并把资产置为 `SETTLED`，后到的普通评估全部被业务规则拒绝（`400`）；
- 新评估先拿到锁：版本号被推进，基于旧 `expectedVersion` 的结算得到 `409`（错误码 `VERSION_CONFLICT`），需重新查询最新版本后再提交。

资产状态、冻结依据、最终金额、明细在**同一数据库事务**内提交；任一步骤失败整体回滚，不留下结算或明细，资产也不会停留在 `SETTLED`。数据库层 `residual_settlement.asset_id` 唯一约束兜底，保证一个资产至多一笔结算；`settlement_no` 全局唯一。

**幂等**：相同 `settlementNo` 连同相同资产、`expectedVersion`、处置收入、处置日期、规则代码重复提交时视为重试，幂等返回**首次结算结果**（HTTP `200`，响应 `replayed` 为 `true`），不产生新结算或明细；编号复用但任一内容不一致返回 `409`（`DUPLICATE_RESOURCE`）。`settlementDate` 为服务端确认时间，不参与幂等内容比对。已结算资产用**不同**编号再次确认返回 `400`，提示走更正流程。

错误情况：资产不存在 `404`；资产尚无评估、处置日期早于评估日期、结算日期早于处置日期返回 `400`；`expectedVersion` 过期返回 `409`；字段格式校验失败返回 `400` 并在 `errors` 中列出字段原因。

### 追加结算更正

`POST /api/assets/{assetCode}/residual-settlements/corrections`

结算成功后需要修正时，**只能**通过本接口追加更正，不提供覆盖、更新或删除原始结算的入口。请求体：

```json
{
  "correctionNo": "COR-20250510-001",
  "correctionDate": "2025-05-10",
  "reason": "处置费用与评估口径重估",
  "adjustedResidualValue": 78000.00,
  "adjustedDisposalIncome": 72000.00
}
```

- `correctionNo`：全局唯一的更正编号；
- `correctionDate`：更正日期，不得早于结算日期，且必须严格晚于上一笔更正日期；
- `reason`：更正原因；
- `adjustedResidualValue` / `adjustedDisposalIncome`：更正后采用的**有效评估残值**与**有效实际处置收入**，取值 [0, 资产原值] / 非负。

服务端按更正后口径重算有效结算差额、方向、应补/应退金额，并记录相对上一有效结果（首笔更正相对原始结算）的影响额（`diffChange`、`payableChange`、`residualValueChange`、`disposalIncomeChange`）。成功返回 `201`，响应仍为该资产的结算完整视图：

- 原始冻结依据（`valuationBasis`、`contractBasis`）与原始结果（`disposalIncome`、`settlementDiff`、`payableAmount` 等）**保持不变**；
- `corrections` 为按 `seqNo`（从 1 起）升序的只追加更正链；
- `appliedCorrectionCount` 为已应用更正笔数；
- `effectiveResidualValue`、`effectiveDisposalIncome`、`effectiveSettlementDiff`、`effectiveDiffDirection`、`effectivePayableAmount`、`effectiveRefundableAmount` 为截至最近一笔更正后的有效金额（无更正时等于原始结果）。

更正同样幂等：相同 `correctionNo` 连同相同更正日期、原因、有效残值、有效收入重试返回首次结果（`200`，`replayed=true`）；编号复用但内容不一致返回 `409`。资产未结算时更正返回 `400`；更正写入与结算有效金额更新在同一事务提交，失败整体回滚。

### 查询残值结算

- `GET /api/assets/{assetCode}/residual-settlements/current`：按资产查询结算完整计算依据（冻结评估、冻结合同规则、原始金额与明细、有效金额、更正链）。资产不存在或尚未结算返回 `404`。
- `GET /api/residual-settlements/{settlementNo}`：按结算编号查询同一完整视图，结算不存在返回 `404`。

### 校验与错误响应

- 租赁物编码、合同编号分别唯一，重复返回 `409`。
- 残值评估编号全局唯一，复用且内容不一致返回 `409`；登记所基于的版本过期或评估日期不晚于最新版本返回 `409`（错误码 `VERSION_CONFLICT`）。
- 结算编号、更正编号全局唯一，复用且内容不一致返回 `409`；结算所基于的评估版本过期（被并发新评估推进）返回 `409`（`VERSION_CONFLICT`）。
- 资产完成残值结算后不再接受普通评估，重复结算或对未结算资产发起更正返回 `400`，修正请走只追加的结算更正流程。
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

## 残值评估版本链规则

- 评估记录持久化在 `asset_valuation` 表：外键 `asset_id` 指向租赁物；`valuation_no` 全局唯一（`uk_asset_valuation_no`），`(asset_id, version_no)` 设有联合唯一约束（`uk_asset_valuation_asset_version`）；版本链只增不改，无更新与删除路径。
- `version_no` 由服务端在登记事务内按“该资产当前最大版本号 + 1”分配，从 1 起连续递增；登记失败整体回滚，因此不会出现跳号版本或不完整记录。
- 减值金额 = 资产原值 − 评估价值，保留 **2 位小数（HALF_UP）**；残值率 = 评估价值 ÷ 资产原值，保留 **6 位小数（HALF_UP）**；评估价值入库存量到 2 位小数。全部金额与比率使用 `BigDecimal`，不使用 `double`/`float`。
- 登记事务先对 `leased_asset` 资产行加悲观写锁（`SELECT … FOR UPDATE`），在锁内读取最新版本并完成版本匹配（`expectedVersion`）、评估日期严格递增校验后写入；联合唯一约束兜底，保证同一资产并发登记只有一个基于当前版本的请求成功，其余返回 `409`。
- 相同编号、相同资产、日期、价值、机构的重复提交命中幂等分支，返回首次结果；编号复用但内容不一致返回 `409`。
- 历史查询按版本号升序（同级按 id 升序）稳定排序，每条记录标注 `latest`；当前版本即版本号最大的记录。
- 业务校验：评估日期不得早于合同起租日；评估价值取值区间为 [0, 资产原值]，边界 0 与原值均合法（分别对应残值率 0 与 1）。
- 资产完成残值结算后进入 `SETTLED`：普通评估登记被拒绝（`400`）；历史评估编号的相同内容幂等重放仍返回首次结果，评估版本链保持不可变。

## 残值结算规则

- 结算主表 `residual_settlement`：`settlement_no` 全局唯一（`uk_residual_settlement_no`），`asset_id` 唯一（`uk_residual_settlement_asset`，一个资产至多一笔结算）。明细表 `residual_settlement_item` 以 `(settlement_id, line_no)` 唯一约束固定有序明细；更正表 `residual_settlement_correction` 的 `correction_no` 全局唯一、`(settlement_id, seq_no)` 唯一，构成只追加更正链。
- 确认结算在单个事务内对租赁物行加悲观写锁（与评估登记共用同一把锁），在锁内读取最新评估版本并比对 `expectedVersion`，随后冻结评估依据、合同计算规则、实际处置收入，计算结算差额与明细，并把资产置为 `SETTLED`，最后一起提交。新评估与结算并发时在锁上串行，叠加版本比对只允许一方成功；资产唯一约束兜底。
- 冻结依据以标量列复制到结算主表（评估版本号/编号/日期/残值/减值/残值率/机构，合同编号/起租日/原值/融资金额/年利率/还款方式/结算规则代码），事后评估链或更正均不回改这些列，因此结算采用的原始依据永不被覆盖。
- 结算差额 = 评估残值 − 实际处置收入，保留 **2 位小数（HALF_UP）**；方向 `PAYABLE`/`REFUNDABLE`/`EVEN`，应补金额为正差额、应退金额为负差额绝对值，二者互斥，另一者为 0。明细三行（计入评估残值、冲减处置收入、结果差额）的带符号金额满足「计入 + 冲减 = 结果」。
- 更正只追加：每笔更正保存更正后的有效残值/收入、重算的有效差额/方向/应补应退，以及相对上一有效结果的影响额；原始结算的冻结依据与原始金额不变，主表上的 `effective_*` 字段随最近一笔更正更新。更正日期须晚于结算日期且严格晚于上一笔更正日期。
- 结算编号与更正编号均支持相同内容重放幂等（分别返回 `200`，`replayed=true`），编号复用且内容不一致返回 `409`。
- 资产状态、结算金额、明细（及更正与其有效金额）必须在同一事务提交；任一步骤失败整体回滚，不留下部分结算、部分明细或未完成的状态翻转。

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

登记首次残值评估（基于版本 0）：

    curl -X POST http://localhost:8080/api/assets/ASSET-001/valuations \
      -H "Content-Type: application/json" \
      -d '{"valuationNo":"VAL-20250301-001","expectedVersion":0,"valuationDate":"2025-03-01","residualValue":120000.00,"institution":"中评评估机构"}'

基于最新版本 1 追加下一次评估：

    curl -X POST http://localhost:8080/api/assets/ASSET-001/valuations \
      -H "Content-Type: application/json" \
      -d '{"valuationNo":"VAL-20250601-001","expectedVersion":1,"valuationDate":"2025-06-01","residualValue":90000.00,"institution":"中评评估机构"}'

查询评估历史与当前版本：

    curl http://localhost:8080/api/assets/ASSET-001/valuations

    curl http://localhost:8080/api/assets/ASSET-001/valuations/current

基于最新评估版本 2 与实际处置收入确认残值结算：

    curl -X POST http://localhost:8080/api/assets/ASSET-001/residual-settlements \
      -H "Content-Type: application/json" \
      -d '{"settlementNo":"STL-20250401-001","expectedVersion":2,"disposalIncome":48000.00,"disposalDate":"2025-04-01","settlementDate":"2025-04-02","settlementRuleCode":"RESIDUAL_VS_DISPOSAL"}'

结算后追加一笔更正（只追加，不覆盖原始冻结依据）：

    curl -X POST http://localhost:8080/api/assets/ASSET-001/residual-settlements/corrections \
      -H "Content-Type: application/json" \
      -d '{"correctionNo":"COR-20250510-001","correctionDate":"2025-05-10","reason":"处置费用与评估口径重估","adjustedResidualValue":52000.00,"adjustedDisposalIncome":49000.00}'

查询结算完整计算依据（按资产或按结算编号）：

    curl http://localhost:8080/api/assets/ASSET-001/residual-settlements/current

    curl http://localhost:8080/api/residual-settlements/STL-20250401-001
