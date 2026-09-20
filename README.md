# LeaseFlow

LeaseFlow 是一个面向融资租赁场景的 Spring Boot 后端项目，用于承载租赁资产、合同租金及其相关业务数据。当前已实现业务功能：创建融资租赁项目，并按等额本金或等额本息方式生成月度租金计划。

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
