package com.leaseflow.lease;

import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.contract.RepaymentMethod;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.schedule.PaymentScheduleItem;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LeaseApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeasedAssetRepository assetRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private PaymentScheduleItemRepository scheduleItemRepository;

    private String requestJson(String assetCode, String contractNo, String originalValue,
                               String financingAmount, String annualRate, int termMonths,
                               String startDate, String firstPaymentDate) {
        return requestJson(assetCode, contractNo, originalValue, financingAmount, annualRate,
                termMonths, startDate, firstPaymentDate, null);
    }

    private String requestJson(String assetCode, String contractNo, String originalValue,
                               String financingAmount, String annualRate, int termMonths,
                               String startDate, String firstPaymentDate, String repaymentMethod) {
        String repaymentMethodLine = repaymentMethod == null
                ? ""
                : ", \"repaymentMethod\": \"%s\"".formatted(repaymentMethod);
        return """
                {
                  "assetCode": "%s",
                  "assetName": "数控机床",
                  "category": "生产设备",
                  "originalValue": %s,
                  "contractNo": "%s",
                  "startDate": "%s",
                  "firstPaymentDate": "%s",
                  "financingAmount": %s,
                  "nominalAnnualRate": %s,
                  "termMonths": %d%s
                }
                """.formatted(assetCode, originalValue, contractNo, startDate,
                firstPaymentDate, financingAmount, annualRate, termMonths, repaymentMethodLine);
    }

    private JsonNode postLease(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static void assertMoney(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue())
                .as(field)
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void createsEqualPrincipalScheduleAndPersists() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-001", "HT-001", "150000",
                "120000", "0.12", 12, "2025-01-01", "2025-02-01"));

        assertThat(response.get("asset").get("assetCode").asText()).isEqualTo("ASSET-001");
        assertThat(response.get("contract").get("contractNo").asText()).isEqualTo("HT-001");
        assertThat(response.get("schedule").size()).isEqualTo(12);

        JsonNode first = response.get("schedule").get(0);
        assertThat(first.get("periodNo").asInt()).isEqualTo(1);
        assertThat(first.get("dueDate").asText()).isEqualTo("2025-02-01");
        assertMoney(first, "openingPrincipal", "120000.00");
        assertMoney(first, "principalDue", "10000.00");
        assertMoney(first, "interestDue", "1200.00");
        assertMoney(first, "totalDue", "11200.00");
        assertMoney(first, "closingPrincipal", "110000.00");

        JsonNode last = response.get("schedule").get(11);
        assertThat(last.get("dueDate").asText()).isEqualTo("2026-01-01");
        assertMoney(last, "openingPrincipal", "10000.00");
        assertMoney(last, "interestDue", "100.00");
        assertMoney(last, "closingPrincipal", "0.00");

        assertMoney(response.get("summary"), "totalPrincipal", "120000.00");
        assertMoney(response.get("summary"), "totalInterest", "7800.00");
        assertMoney(response.get("summary"), "totalAmount", "127800.00");

        // 汇总必须与明细求和一致
        BigDecimal sumPrincipal = BigDecimal.ZERO;
        BigDecimal sumInterest = BigDecimal.ZERO;
        BigDecimal sumTotal = BigDecimal.ZERO;
        for (JsonNode item : response.get("schedule")) {
            sumPrincipal = sumPrincipal.add(item.get("principalDue").decimalValue());
            sumInterest = sumInterest.add(item.get("interestDue").decimalValue());
            sumTotal = sumTotal.add(item.get("totalDue").decimalValue());
        }
        assertThat(sumPrincipal).isEqualByComparingTo("120000.00");
        assertThat(sumInterest).isEqualByComparingTo("7800.00");
        assertThat(sumTotal).isEqualByComparingTo("127800.00");

        // 持久化验证
        assertThat(assetRepository.existsByAssetCode("ASSET-001")).isTrue();
        var contract = contractRepository.findByContractNo("HT-001").orElseThrow();
        List<PaymentScheduleItem> persisted = scheduleItemRepository
                .findByContractIdOrderByPeriodNoAsc(contract.getId());
        assertThat(persisted).hasSize(12);
        assertThat(persisted.get(0).getPrincipalDue()).isEqualByComparingTo("10000.00");
        assertThat(persisted.get(11).getClosingPrincipal()).isEqualByComparingTo("0.00");

        // 查询接口返回相同数据且按期次升序
        MvcResult getResult = mockMvc.perform(get("/api/leases/HT-001"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(getResult.getResponse().getContentAsString());
        assertThat(detail.get("schedule").size()).isEqualTo(12);
        for (int i = 0; i < 12; i++) {
            assertThat(detail.get("schedule").get(i).get("periodNo").asInt()).isEqualTo(i + 1);
        }
        assertMoney(detail.get("summary"), "totalAmount", "127800.00");
    }

    @Test
    void createsZeroRateSchedule() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-002", "HT-002", "8000",
                "6000", "0", 6, "2025-03-01", "2025-04-01"));

        assertThat(response.get("schedule").size()).isEqualTo(6);
        for (JsonNode item : response.get("schedule")) {
            assertMoney(item, "interestDue", "0.00");
            assertMoney(item, "principalDue", "1000.00");
            assertMoney(item, "totalDue", "1000.00");
        }
        assertMoney(response.get("summary"), "totalInterest", "0.00");
        assertMoney(response.get("summary"), "totalPrincipal", "6000.00");
        assertMoney(response.get("summary"), "totalAmount", "6000.00");
    }

    @Test
    void createsSinglePeriodSchedule() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-003", "HT-003", "6000",
                "5000", "0.06", 1, "2025-01-15", "2025-02-15"));

        assertThat(response.get("schedule").size()).isEqualTo(1);
        JsonNode only = response.get("schedule").get(0);
        assertMoney(only, "openingPrincipal", "5000.00");
        assertMoney(only, "principalDue", "5000.00");
        assertMoney(only, "interestDue", "25.00");
        assertMoney(only, "totalDue", "5025.00");
        assertMoney(only, "closingPrincipal", "0.00");
        assertMoney(response.get("summary"), "totalPrincipal", "5000.00");
        assertMoney(response.get("summary"), "totalInterest", "25.00");
    }

    @Test
    void anchorsDueDatesToFirstPaymentDateForMonthEnds() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-004", "HT-004", "30000",
                "30000", "0", 4, "2025-01-01", "2025-01-31"));

        List<String> expectedDueDates = List.of("2025-01-31", "2025-02-28",
                "2025-03-31", "2025-04-30");
        for (int i = 0; i < expectedDueDates.size(); i++) {
            assertThat(response.get("schedule").get(i).get("dueDate").asText())
                    .isEqualTo(expectedDueDates.get(i));
        }
    }

    @Test
    void lastPeriodAbsorbsRoundingDifference() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-005", "HT-005", "10000",
                "10000", "0", 3, "2025-01-01", "2025-02-01"));

        JsonNode schedule = response.get("schedule");
        assertMoney(schedule.get(0), "principalDue", "3333.33");
        assertMoney(schedule.get(1), "principalDue", "3333.33");
        assertMoney(schedule.get(2), "principalDue", "3333.34");
        assertMoney(schedule.get(2), "closingPrincipal", "0.00");
        assertMoney(response.get("summary"), "totalPrincipal", "10000.00");

        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode item : schedule) {
            assertThat(item.get("principalDue").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(item.get("interestDue").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            sum = sum.add(item.get("principalDue").decimalValue());
        }
        assertThat(sum).isEqualByComparingTo("10000.00");
    }

    @Test
    void rejectsFinancingAmountAboveOriginalValue() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ASSET-101", "HT-101", "10000",
                                "10000.01", "0.1", 12, "2025-01-01", "2025-02-01")))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(error.get("message").asText()).contains("融资金额不得超过租赁物原值");
        assertThat(error.get("timestamp").asText()).isNotBlank();
        assertThat(contractRepository.findByContractNo("HT-101")).isEmpty();
    }

    @Test
    void rejectsFirstPaymentDateBeforeStartDate() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ASSET-102", "HT-102", "10000",
                                "8000", "0.1", 12, "2025-02-01", "2025-01-31")))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(error.get("message").asText()).contains("首期应还日不得早于起租日");
    }

    @Test
    void rejectsInvalidFieldsWithFieldDetails() throws Exception {
        String body = """
                {
                  "assetCode": "",
                  "assetName": "数控机床",
                  "category": "生产设备",
                  "originalValue": 0,
                  "contractNo": "HT-103",
                  "startDate": "2025-01-01",
                  "firstPaymentDate": "2025-02-01",
                  "financingAmount": -5,
                  "nominalAnnualRate": 1.5,
                  "termMonths": 0
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("timestamp").asText()).isNotBlank();

        List<String> fields = new java.util.ArrayList<>();
        error.get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("assetCode", "originalValue", "financingAmount",
                "nominalAnnualRate", "termMonths");
        for (JsonNode fieldError : error.get("errors")) {
            assertThat(fieldError.get("reason").asText()).isNotBlank();
        }
    }

    @Test
    void rejectsTermMonthsAbove120() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ASSET-104", "HT-104", "10000",
                                "8000", "0.1", 121, "2025-01-01", "2025-02-01")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateAssetCodeAndContractNo() throws Exception {
        postLease(requestJson("ASSET-201", "HT-201", "10000",
                "8000", "0.1", 12, "2025-01-01", "2025-02-01"));

        // 重复租赁物编码
        MvcResult dupAsset = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ASSET-201", "HT-202", "10000",
                                "8000", "0.1", 12, "2025-01-01", "2025-02-01")))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode error = objectMapper.readTree(dupAsset.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(error.get("timestamp").asText()).isNotBlank();

        // 重复合同编号
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ASSET-202", "HT-201", "10000",
                                "8000", "0.1", 12, "2025-01-01", "2025-02-01")))
                .andExpect(status().isConflict());

        // 冲突请求不得留下部分数据
        assertThat(contractRepository.findByContractNo("HT-202")).isEmpty();
        assertThat(assetRepository.existsByAssetCode("ASSET-202")).isFalse();
    }

    @Test
    void returns404ForUnknownContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/leases/NO-SUCH-CONTRACT"))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(error.get("message").asText()).contains("NO-SUCH-CONTRACT");
        assertThat(error.get("timestamp").asText()).isNotBlank();
    }

    @Test
    void createsEqualPaymentScheduleAndPersists() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-301", "HT-301", "150000",
                "120000", "0.12", 12, "2025-01-01", "2025-02-01", "EQUAL_PAYMENT"));

        assertThat(response.get("contract").get("repaymentMethod").asText())
                .isEqualTo("EQUAL_PAYMENT");
        assertThat(response.get("schedule").size()).isEqualTo(12);

        // 月供 10661.85，前 11 期应还总额固定
        for (int i = 0; i < 11; i++) {
            assertMoney(response.get("schedule").get(i), "totalDue", "10661.85");
        }
        JsonNode first = response.get("schedule").get(0);
        assertThat(first.get("dueDate").asText()).isEqualTo("2025-02-01");
        assertMoney(first, "openingPrincipal", "120000.00");
        assertMoney(first, "interestDue", "1200.00");
        assertMoney(first, "principalDue", "9461.85");
        assertMoney(first, "closingPrincipal", "110538.15");

        // 最后一期以剩余本金结清，应还总额 = 剩余本金 + 当期利息
        JsonNode last = response.get("schedule").get(11);
        assertMoney(last, "openingPrincipal", "10556.35");
        assertMoney(last, "principalDue", "10556.35");
        assertMoney(last, "interestDue", "105.56");
        assertMoney(last, "totalDue", "10661.91");
        assertMoney(last, "closingPrincipal", "0.00");

        assertMoney(response.get("summary"), "totalPrincipal", "120000.00");
        assertMoney(response.get("summary"), "totalInterest", "7942.26");
        assertMoney(response.get("summary"), "totalAmount", "127942.26");

        // 汇总与明细一致，所有金额非负，本金合计等于融资金额
        BigDecimal sumPrincipal = BigDecimal.ZERO;
        BigDecimal sumInterest = BigDecimal.ZERO;
        BigDecimal sumTotal = BigDecimal.ZERO;
        for (JsonNode item : response.get("schedule")) {
            assertThat(item.get("principalDue").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(item.get("interestDue").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(item.get("totalDue").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            sumPrincipal = sumPrincipal.add(item.get("principalDue").decimalValue());
            sumInterest = sumInterest.add(item.get("interestDue").decimalValue());
            sumTotal = sumTotal.add(item.get("totalDue").decimalValue());
        }
        assertThat(sumPrincipal).isEqualByComparingTo("120000.00");
        assertThat(sumInterest).isEqualByComparingTo("7942.26");
        assertThat(sumTotal).isEqualByComparingTo("127942.26");

        // 还款方式持久化
        var contract = contractRepository.findByContractNo("HT-301").orElseThrow();
        assertThat(contract.getRepaymentMethod()).isEqualTo(RepaymentMethod.EQUAL_PAYMENT);
        List<PaymentScheduleItem> persisted = scheduleItemRepository
                .findByContractIdOrderByPeriodNoAsc(contract.getId());
        assertThat(persisted).hasSize(12);
        assertThat(persisted.get(0).getTotalDue()).isEqualByComparingTo("10661.85");
        assertThat(persisted.get(11).getTotalDue()).isEqualByComparingTo("10661.91");
        assertThat(persisted.get(11).getClosingPrincipal()).isEqualByComparingTo("0.00");

        // 查询接口返回的还款方式、计划与汇总与创建结果一致
        MvcResult getResult = mockMvc.perform(get("/api/leases/HT-301"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(getResult.getResponse().getContentAsString());
        assertThat(detail.get("contract").get("repaymentMethod").asText())
                .isEqualTo("EQUAL_PAYMENT");
        assertThat(detail.get("schedule").size()).isEqualTo(12);
        for (int i = 0; i < 12; i++) {
            JsonNode created = response.get("schedule").get(i);
            JsonNode queried = detail.get("schedule").get(i);
            assertThat(queried.get("periodNo").asInt()).isEqualTo(created.get("periodNo").asInt());
            assertThat(queried.get("dueDate").asText()).isEqualTo(created.get("dueDate").asText());
            for (String moneyField : List.of("openingPrincipal", "principalDue", "interestDue",
                    "totalDue", "closingPrincipal")) {
                assertThat(queried.get(moneyField).decimalValue())
                        .as("period %d %s", i + 1, moneyField)
                        .isEqualByComparingTo(created.get(moneyField).decimalValue());
            }
        }
        assertMoney(detail.get("summary"), "totalPrincipal", "120000.00");
        assertMoney(detail.get("summary"), "totalInterest", "7942.26");
        assertMoney(detail.get("summary"), "totalAmount", "127942.26");
    }

    @Test
    void createsEqualPaymentZeroRateSchedule() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-302", "HT-302", "8000",
                "6000", "0", 6, "2025-03-01", "2025-04-01", "EQUAL_PAYMENT"));

        assertThat(response.get("contract").get("repaymentMethod").asText())
                .isEqualTo("EQUAL_PAYMENT");
        // 零利率时按融资金额平均分摊
        for (JsonNode item : response.get("schedule")) {
            assertMoney(item, "interestDue", "0.00");
            assertMoney(item, "principalDue", "1000.00");
            assertMoney(item, "totalDue", "1000.00");
        }
        assertMoney(response.get("summary"), "totalPrincipal", "6000.00");
        assertMoney(response.get("summary"), "totalInterest", "0.00");
        assertMoney(response.get("summary"), "totalAmount", "6000.00");
    }

    @Test
    void createsEqualPaymentSinglePeriodSchedule() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-303", "HT-303", "6000",
                "5000", "0.06", 1, "2025-01-15", "2025-02-15", "EQUAL_PAYMENT"));

        assertThat(response.get("schedule").size()).isEqualTo(1);
        JsonNode only = response.get("schedule").get(0);
        assertMoney(only, "openingPrincipal", "5000.00");
        assertMoney(only, "principalDue", "5000.00");
        assertMoney(only, "interestDue", "25.00");
        assertMoney(only, "totalDue", "5025.00");
        assertMoney(only, "closingPrincipal", "0.00");
        assertMoney(response.get("summary"), "totalPrincipal", "5000.00");
        assertMoney(response.get("summary"), "totalInterest", "25.00");
        assertMoney(response.get("summary"), "totalAmount", "5025.00");
    }

    @Test
    void equalPaymentLastPeriodAbsorbsRoundingDifference() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-304", "HT-304", "10000",
                "10000", "0.10", 3, "2025-01-01", "2025-02-01", "EQUAL_PAYMENT"));

        JsonNode schedule = response.get("schedule");
        // 月供 3389.04，最后一期吸收累计舍入差额后为 3389.05
        assertMoney(schedule.get(0), "interestDue", "83.33");
        assertMoney(schedule.get(0), "principalDue", "3305.71");
        assertMoney(schedule.get(0), "totalDue", "3389.04");
        assertMoney(schedule.get(1), "interestDue", "55.79");
        assertMoney(schedule.get(1), "principalDue", "3333.25");
        assertMoney(schedule.get(1), "totalDue", "3389.04");
        assertMoney(schedule.get(2), "openingPrincipal", "3361.04");
        assertMoney(schedule.get(2), "interestDue", "28.01");
        assertMoney(schedule.get(2), "principalDue", "3361.04");
        assertMoney(schedule.get(2), "totalDue", "3389.05");
        assertMoney(schedule.get(2), "closingPrincipal", "0.00");

        BigDecimal sumPrincipal = BigDecimal.ZERO;
        for (JsonNode item : schedule) {
            assertThat(item.get("principalDue").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(item.get("interestDue").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            sumPrincipal = sumPrincipal.add(item.get("principalDue").decimalValue());
        }
        assertThat(sumPrincipal).isEqualByComparingTo("10000.00");
        assertMoney(response.get("summary"), "totalPrincipal", "10000.00");
        assertMoney(response.get("summary"), "totalInterest", "167.13");
        assertMoney(response.get("summary"), "totalAmount", "10167.13");
    }

    @Test
    void defaultsToEqualPrincipalWhenRepaymentMethodOmitted() throws Exception {
        JsonNode response = postLease(requestJson("ASSET-305", "HT-305", "150000",
                "120000", "0.12", 12, "2025-01-01", "2025-02-01"));

        assertThat(response.get("contract").get("repaymentMethod").asText())
                .isEqualTo("EQUAL_PRINCIPAL");
        JsonNode first = response.get("schedule").get(0);
        assertMoney(first, "principalDue", "10000.00");
        assertMoney(first, "interestDue", "1200.00");
        assertMoney(first, "totalDue", "11200.00");
        assertMoney(response.get("summary"), "totalAmount", "127800.00");

        var contract = contractRepository.findByContractNo("HT-305").orElseThrow();
        assertThat(contract.getRepaymentMethod()).isEqualTo(RepaymentMethod.EQUAL_PRINCIPAL);

        MvcResult getResult = mockMvc.perform(get("/api/leases/HT-305"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detail = objectMapper.readTree(getResult.getResponse().getContentAsString());
        assertThat(detail.get("contract").get("repaymentMethod").asText())
                .isEqualTo("EQUAL_PRINCIPAL");
        assertMoney(detail.get("summary"), "totalAmount", "127800.00");
    }

    @Test
    void rejectsInvalidRepaymentMethod() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ASSET-306", "HT-306", "10000",
                                "8000", "0.1", 12, "2025-01-01", "2025-02-01", "MONTHLY")))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("timestamp").asText()).isNotBlank();
        assertThat(error.get("errors")).hasSize(1);
        JsonNode fieldError = error.get("errors").get(0);
        assertThat(fieldError.get("field").asText()).isEqualTo("repaymentMethod");
        assertThat(fieldError.get("reason").asText()).contains("MONTHLY");
        assertThat(contractRepository.findByContractNo("HT-306")).isEmpty();
    }
}
