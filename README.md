# LeaseFlow

LeaseFlow 是一个面向融资租赁场景的 Spring Boot 后端项目，用于承载租赁资产、合同租金及其相关业务数据。当前版本实现了首个业务功能：创建融资租赁项目，并按等额本金方式生成月度租金计划。

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
  "asset": {
    "assetCode": "A-001",
    "assetName": "数控机床",
    "category": "机械设备",
    "originalValue": 200000.00
  },
  "contract": {
    "contractNo": "C-001",
    "startDate": "2026-01-15",
    "firstDueDate": "2026-02-01",
    "financingAmount": 120000.00,
    "annualRate": 0.06,
    "periods": 12
  }
}
```

成功返回 `201 Created`，响应包含租赁物、合同、完整租金计划（`schedule`）及汇总金额（`summary`，含本金合计、利息合计、租金合计）。

校验规则：

- 租赁物编码、合同编号分别唯一，重复返回 `409`
- 原值、融资金额必须大于 0，且融资金额不得超过原值
- 名义年利率取值 0 到 1（允许为 0），期数 1 到 120
- 首期应还日不得早于起租日
- 参数或业务校验失败返回 `400`

错误响应为统一结构，包含 `code`、`message`、`timestamp`；字段校验失败时 `errors` 中列出字段及原因。

### 查询租赁项目

`GET /api/leases/{contractNo}`

返回租赁项目详情及按期次升序排列的租金计划；合同不存在返回 `404`。

## 租金计算规则（等额本金）

- 每期应还本金 = 融资金额 ÷ 期数，保留 2 位小数（HALF_UP）；最后一期承担累计舍入差额，保证本金合计严格等于融资金额
- 每期利息 = 期初剩余本金 ×（名义年利率 ÷ 12），保留 2 位小数（HALF_UP）
- 每期应还总额 = 应还本金 + 应还利息；最后一期期末本金为 0，所有金额不为负数
- 每期应还日以首期应还日为锚点逐月增加（`firstDueDate.plusMonths(n - 1)`），避免月末日期漂移
- 所有金额与利率计算均使用 `BigDecimal`，不使用 `double`/`float`
- 租赁物、合同、租金计划通过 JPA 在同一事务中持久化，数据库层设有唯一约束

## 常用命令

运行测试：

    JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw test

启动应用：

    JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw spring-boot:run

（若系统默认 JDK 已是 21，可直接 `./mvnw test` / `./mvnw spring-boot:run`。）
