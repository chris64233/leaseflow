package com.leaseflow.payment;

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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RentPaymentApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private PaymentScheduleItemRepository scheduleItemRepository;

    @Autowired
    private RentPaymentRepository paymentRepository;

    private static final String START_DATE = "2025-01-01";
    private static final String FIRST_PAYMENT_DATE = "2025-02-01";

    private void createLease(String assetCode, String contractNo, String financingAmount,
                             int termMonths, String annualRate) {
        String body = """
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
                  "termMonths": %d
                }
                """.formatted(assetCode, financingAmount, contractNo, START_DATE,
                FIRST_PAYMENT_DATE, financingAmount, annualRate, termMonths);
        try {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult registerPayment(String contractNo, int periodNo, String paymentNo,
                                      String amount, String paymentDate) throws Exception {
        String body = """
                {
                  "paymentNo": "%s",
                  "amount": %s,
                  "paymentDate": "%s"
                }
                """.formatted(paymentNo, amount, paymentDate);
        return mockMvc.perform(post(
                                "/api/leases/{contractNo}/schedule/{periodNo}/payments",
                                contractNo, periodNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static void assertMoney(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue())
                .as(field)
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    private PaymentScheduleItem persistedItem(String contractNo, int periodNo) {
        var contract = contractRepository.findByContractNo(contractNo).orElseThrow();
        return scheduleItemRepository.findByContractIdAndPeriodNo(contract.getId(), periodNo)
                .orElseThrow();
    }

    private long persistedPaymentCount(String contractNo, int periodNo) {
        return paymentRepository.countByScheduleItemId(persistedItem(contractNo, periodNo).getId());
    }

    @Test
    void firstPaymentRegistersAsPartial() throws Exception {
        createLease("ASSET-401", "HT-401", "120000", 12, "0.12");

        MvcResult result = registerPayment("HT-401", 1, "PAY-401-1", "5000.00", "2025-02-05");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        JsonNode body = json(result);
        JsonNode payment = body.get("payment");
        assertThat(payment.get("paymentNo").asText()).isEqualTo("PAY-401-1");
        assertThat(payment.get("periodNo").asInt()).isEqualTo(1);
        assertMoney(payment, "amount", "5000.00");
        assertThat(payment.get("paymentDate").asText()).isEqualTo("2025-02-05");

        JsonNode period = body.get("period");
        assertThat(period.get("periodNo").asInt()).isEqualTo(1);
        assertMoney(period, "totalDue", "11200.00");
        assertMoney(period, "paidAmount", "5000.00");
        assertMoney(period, "outstandingAmount", "6200.00");
        assertThat(period.get("paymentStatus").asText()).isEqualTo("PARTIAL");

        PaymentScheduleItem item = persistedItem("HT-401", 1);
        assertThat(item.getPaidAmount()).isEqualByComparingTo("5000.00");
        assertThat(persistedPaymentCount("HT-401", 1)).isEqualTo(1);
    }

    @Test
    void multiplePaymentsAccumulateUntilPaid() throws Exception {
        createLease("ASSET-402", "HT-402", "120000", 12, "0.12");

        MvcResult first = registerPayment("HT-402", 1, "PAY-402-1", "5000", "2025-02-05");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstPeriod = json(first).get("period");
        assertMoney(firstPeriod, "paidAmount", "5000.00");
        assertMoney(firstPeriod, "outstandingAmount", "6200.00");
        assertThat(firstPeriod.get("paymentStatus").asText()).isEqualTo("PARTIAL");

        MvcResult second = registerPayment("HT-402", 1, "PAY-402-2", "6200.00", "2025-02-10");
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        JsonNode secondPeriod = json(second).get("period");
        assertMoney(secondPeriod, "totalDue", "11200.00");
        assertMoney(secondPeriod, "paidAmount", "11200.00");
        assertMoney(secondPeriod, "outstandingAmount", "0.00");
        assertThat(secondPeriod.get("paymentStatus").asText()).isEqualTo("PAID");

        PaymentScheduleItem item = persistedItem("HT-402", 1);
        assertThat(item.getPaidAmount()).isEqualByComparingTo("11200.00");
        assertThat(item.getTotalDue()).isEqualByComparingTo("11200.00");
        assertThat(persistedPaymentCount("HT-402", 1)).isEqualTo(2);

        MvcResult detail = mockMvc.perform(get("/api/leases/HT-402")).andReturn();
        JsonNode firstItem = json(detail).get("schedule").get(0);
        assertMoney(firstItem, "paidAmount", "11200.00");
        assertMoney(firstItem, "outstandingAmount", "0.00");
        assertThat(firstItem.get("paymentStatus").asText()).isEqualTo("PAID");
    }

    @Test
    void fullPaymentRegistersAsPaid() throws Exception {
        createLease("ASSET-403", "HT-403", "6000", 3, "0");

        MvcResult result = registerPayment("HT-403", 1, "PAY-403-1", "2000.00", "2025-02-01");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode period = json(result).get("period");
        assertMoney(period, "totalDue", "2000.00");
        assertMoney(period, "paidAmount", "2000.00");
        assertMoney(period, "outstandingAmount", "0.00");
        assertThat(period.get("paymentStatus").asText()).isEqualTo("PAID");

        assertThat(persistedItem("HT-403", 1).getPaidAmount())
                .isEqualByComparingTo("2000.00");
    }

    @Test
    void rejectsOverPaymentAndRollsBack() throws Exception {
        createLease("ASSET-404", "HT-404", "6000", 3, "0");

        // 首期未收金额 2000，超额回款 2000.01
        MvcResult overPay = registerPayment("HT-404", 1, "PAY-404-X", "2000.01", "2025-02-05");
        assertThat(overPay.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = json(overPay);
        assertThat(error.get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(error.get("message").asText()).contains("未收金额");
        assertThat(error.get("timestamp").asText()).isNotBlank();

        // 部分回款后再次超额
        assertThat(registerPayment("HT-404", 1, "PAY-404-1", "1000.00", "2025-02-05")
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult exceed = registerPayment("HT-404", 1, "PAY-404-2", "1000.01", "2025-02-06");
        assertThat(exceed.getResponse().getStatus()).isEqualTo(400);

        // 事务回滚：失败请求不改变已收金额，也不留回款记录
        PaymentScheduleItem item = persistedItem("HT-404", 1);
        assertThat(item.getPaidAmount()).isEqualByComparingTo("1000.00");
        assertThat(persistedPaymentCount("HT-404", 1)).isEqualTo(1);
        assertThat(paymentRepository.existsByPaymentNo("PAY-404-X")).isFalse();
        assertThat(paymentRepository.existsByPaymentNo("PAY-404-2")).isFalse();

        // 之后仍可正常结清
        MvcResult settle = registerPayment("HT-404", 1, "PAY-404-3", "1000.00", "2025-02-07");
        assertThat(settle.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(settle).get("period").get("paymentStatus").asText()).isEqualTo("PAID");
    }

    @Test
    void rejectsDuplicatePaymentNoGloballyAndRollsBack() throws Exception {
        createLease("ASSET-405", "HT-405", "6000", 3, "0");
        createLease("ASSET-406", "HT-406", "6000", 3, "0");

        assertThat(registerPayment("HT-405", 1, "PAY-DUP-GLOBAL", "2000.00", "2025-02-05")
                .getResponse().getStatus()).isEqualTo(201);

        // 同一合同重复流水号
        MvcResult sameContract = registerPayment("HT-405", 2, "PAY-DUP-GLOBAL",
                "2000.00", "2025-03-05");
        assertThat(sameContract.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = json(sameContract);
        assertThat(error.get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(error.get("message").asText()).contains("PAY-DUP-GLOBAL");
        assertThat(error.get("timestamp").asText()).isNotBlank();

        // 不同合同重复流水号（全局唯一）
        assertThat(registerPayment("HT-406", 1, "PAY-DUP-GLOBAL", "2000.00", "2025-02-05")
                .getResponse().getStatus()).isEqualTo(409);

        // 事务回滚：重复请求不得在目标期次留下回款、不得改变已收金额
        assertThat(persistedItem("HT-405", 2).getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(persistedPaymentCount("HT-405", 2)).isZero();
        assertThat(persistedItem("HT-406", 1).getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(persistedPaymentCount("HT-406", 1)).isZero();
        assertThat(persistedItem("HT-405", 1).getPaidAmount()).isEqualByComparingTo("2000.00");
        assertThat(persistedPaymentCount("HT-405", 1)).isEqualTo(1);
    }

    @Test
    void returns404ForUnknownContractOrPeriod() throws Exception {
        createLease("ASSET-407", "HT-407", "6000", 3, "0");

        MvcResult unknownContract = registerPayment("NO-SUCH-CONTRACT", 1,
                "PAY-407-1", "1.00", "2025-02-05");
        assertThat(unknownContract.getResponse().getStatus()).isEqualTo(404);
        JsonNode contractError = json(unknownContract);
        assertThat(contractError.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(contractError.get("message").asText()).contains("NO-SUCH-CONTRACT");

        MvcResult unknownPeriod = registerPayment("HT-407", 99,
                "PAY-407-2", "1.00", "2025-02-05");
        assertThat(unknownPeriod.getResponse().getStatus()).isEqualTo(404);
        JsonNode periodError = json(unknownPeriod);
        assertThat(periodError.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(periodError.get("message").asText()).contains("HT-407").contains("99");

        assertThat(paymentRepository.existsByPaymentNo("PAY-407-1")).isFalse();
        assertThat(paymentRepository.existsByPaymentNo("PAY-407-2")).isFalse();
    }

    @Test
    void rejectsInvalidPaymentAmountAndMissingFields() throws Exception {
        createLease("ASSET-408", "HT-408", "6000", 3, "0");

        MvcResult zero = registerPayment("HT-408", 1, "PAY-BAD-1", "0", "2025-02-05");
        assertThat(zero.getResponse().getStatus()).isEqualTo(400);
        JsonNode zeroError = json(zero);
        assertThat(zeroError.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> zeroFields = new ArrayList<>();
        zeroError.get("errors").forEach(e -> zeroFields.add(e.get("field").asText()));
        assertThat(zeroFields).contains("amount");

        MvcResult negative = registerPayment("HT-408", 1, "PAY-BAD-2", "-10.00", "2025-02-05");
        assertThat(negative.getResponse().getStatus()).isEqualTo(400);

        String missingFields = """
                {
                  "paymentNo": ""
                }
                """;
        MvcResult missing = mockMvc.perform(post(
                                "/api/leases/{contractNo}/schedule/{periodNo}/payments",
                                "HT-408", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingFields))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode missingError = json(missing);
        List<String> missingFieldNames = new ArrayList<>();
        missingError.get("errors").forEach(e -> missingFieldNames.add(e.get("field").asText()));
        assertThat(missingFieldNames).contains("paymentNo", "amount", "paymentDate");

        // 校验失败不得落库
        assertThat(persistedItem("HT-408", 1).getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(persistedPaymentCount("HT-408", 1)).isZero();
    }

    @Test
    void leaseDetailAndSummaryMatchPersistedPayments() throws Exception {
        createLease("ASSET-409", "HT-409", "6000", 3, "0");

        // 期次 1：部分回款 1500；期次 2：足额回款 2000；期次 3：无回款
        assertThat(registerPayment("HT-409", 1, "PAY-409-1", "1500.00", "2025-02-05")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(registerPayment("HT-409", 2, "PAY-409-3", "2000.00", "2025-03-05")
                .getResponse().getStatus()).isEqualTo(201);

        MvcResult detailResult = mockMvc.perform(get("/api/leases/HT-409")).andReturn();
        assertThat(detailResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode response = json(detailResult);
        JsonNode schedule = response.get("schedule");
        assertThat(schedule.size()).isEqualTo(3);

        assertMoney(schedule.get(0), "totalDue", "2000.00");
        assertMoney(schedule.get(0), "paidAmount", "1500.00");
        assertMoney(schedule.get(0), "outstandingAmount", "500.00");
        assertThat(schedule.get(0).get("paymentStatus").asText()).isEqualTo("PARTIAL");

        assertMoney(schedule.get(1), "totalDue", "2000.00");
        assertMoney(schedule.get(1), "paidAmount", "2000.00");
        assertMoney(schedule.get(1), "outstandingAmount", "0.00");
        assertThat(schedule.get(1).get("paymentStatus").asText()).isEqualTo("PAID");

        assertMoney(schedule.get(2), "totalDue", "2000.00");
        assertMoney(schedule.get(2), "paidAmount", "0.00");
        assertMoney(schedule.get(2), "outstandingAmount", "2000.00");
        assertThat(schedule.get(2).get("paymentStatus").asText()).isEqualTo("UNPAID");

        JsonNode summary = response.get("summary");
        // 原有汇总保持不变
        assertMoney(summary, "totalPrincipal", "6000.00");
        assertMoney(summary, "totalInterest", "0.00");
        assertMoney(summary, "totalAmount", "6000.00");
        // 新增回款汇总
        assertMoney(summary, "totalPaid", "3500.00");
        assertMoney(summary, "totalOutstanding", "2500.00");

        // 明细字段求和与汇总一致
        BigDecimal sumPaid = BigDecimal.ZERO;
        BigDecimal sumOutstanding = BigDecimal.ZERO;
        for (JsonNode item : schedule) {
            sumPaid = sumPaid.add(item.get("paidAmount").decimalValue());
            sumOutstanding = sumOutstanding.add(item.get("outstandingAmount").decimalValue());
            // paidAmount + outstandingAmount = totalDue
            assertThat(item.get("paidAmount").decimalValue()
                    .add(item.get("outstandingAmount").decimalValue()))
                    .isEqualByComparingTo(item.get("totalDue").decimalValue());
            // 原有计划字段保持不变
            assertMoney(item, "principalDue", "2000.00");
            assertMoney(item, "interestDue", "0.00");
        }
        assertThat(sumPaid).isEqualByComparingTo("3500.00");
        assertThat(sumOutstanding).isEqualByComparingTo("2500.00");

        // 查询结果与持久化状态一致
        var contract = contractRepository.findByContractNo("HT-409").orElseThrow();
        List<PaymentScheduleItem> persisted = scheduleItemRepository
                .findByContractIdOrderByPeriodNoAsc(contract.getId());
        assertThat(persisted).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertMoney(schedule.get(i), "paidAmount",
                    persisted.get(i).getPaidAmount().toPlainString());
            assertMoney(schedule.get(i), "totalDue",
                    persisted.get(i).getTotalDue().toPlainString());
            BigDecimal persistedOutstanding = persisted.get(i).getTotalDue()
                    .subtract(persisted.get(i).getPaidAmount());
            assertMoney(schedule.get(i), "outstandingAmount",
                    persistedOutstanding.toPlainString());
        }

        // 未发生回款的合同，查询应返回 UNPAID 与零回款汇总
        createLease("ASSET-410", "HT-410", "6000", 3, "0");
        JsonNode fresh = json(mockMvc.perform(get("/api/leases/HT-410")).andReturn());
        for (JsonNode item : fresh.get("schedule")) {
            assertMoney(item, "paidAmount", "0.00");
            assertMoney(item, "outstandingAmount", "2000.00");
            assertThat(item.get("paymentStatus").asText()).isEqualTo("UNPAID");
        }
        assertMoney(fresh.get("summary"), "totalPaid", "0.00");
        assertMoney(fresh.get("summary"), "totalOutstanding", "6000.00");
    }
}
