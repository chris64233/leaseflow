package com.leaseflow.lease;

import com.leaseflow.lease.domain.LeaseContract;
import com.leaseflow.lease.repository.LeaseContractRepository;
import com.leaseflow.lease.repository.LeasedAssetRepository;
import org.junit.jupiter.api.BeforeEach;
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
class LeaseApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private LeasedAssetRepository assetRepository;

    @BeforeEach
    void cleanDatabase() {
        contractRepository.deleteAll();
        assetRepository.deleteAll();
    }

    @Test
    void createLeaseGeneratesEqualPrincipalSchedule() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-001", "C-001", "120000.00", "0.06", 12,
                                "2026-01-15", "2026-02-01")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = readJson(result);
        JsonNode schedule = body.get("schedule");
        assertThat(schedule).hasSize(12);

        JsonNode first = schedule.get(0);
        assertThat(first.get("periodNo").asInt()).isEqualTo(1);
        assertThat(first.get("dueDate").asText()).isEqualTo("2026-02-01");
        assertMoney(first, "openingPrincipal", "120000.00");
        assertMoney(first, "principal", "10000.00");
        assertMoney(first, "interest", "600.00");
        assertMoney(first, "total", "10600.00");
        assertMoney(first, "closingPrincipal", "110000.00");

        JsonNode last = schedule.get(11);
        assertThat(last.get("periodNo").asInt()).isEqualTo(12);
        assertThat(last.get("dueDate").asText()).isEqualTo("2027-01-01");
        assertMoney(last, "interest", "50.00");
        assertMoney(last, "closingPrincipal", "0.00");

        JsonNode summary = body.get("summary");
        assertMoney(summary, "totalPrincipal", "120000.00");
        assertMoney(summary, "totalInterest", "3900.00");
        assertMoney(summary, "totalAmount", "123900.00");
        assertSummaryEqualsScheduleSum(schedule, summary);

        LeaseContract persisted = contractRepository.findByContractNo("C-001").orElseThrow();
        assertThat(persisted.getScheduleItems()).hasSize(12);
        assertThat(persisted.getAsset().getAssetCode()).isEqualTo("A-001");
        assertThat(assetRepository.existsByAssetCode("A-001")).isTrue();
    }

    @Test
    void createLeaseWithZeroRateProducesZeroInterest() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-002", "C-002", "12000.00", "0", 12,
                                "2026-01-01", "2026-02-01")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = readJson(result);
        for (JsonNode item : body.get("schedule")) {
            assertMoney(item, "interest", "0.00");
            assertThat(item.get("total").decimalValue())
                    .isEqualByComparingTo(item.get("principal").decimalValue());
        }
        JsonNode summary = body.get("summary");
        assertMoney(summary, "totalPrincipal", "12000.00");
        assertMoney(summary, "totalInterest", "0.00");
        assertMoney(summary, "totalAmount", "12000.00");
    }

    @Test
    void createLeaseWithSinglePeriod() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-003", "C-003", "5000.00", "0.12", 1,
                                "2026-03-01", "2026-04-01")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode schedule = readJson(result).get("schedule");
        assertThat(schedule).hasSize(1);
        JsonNode only = schedule.get(0);
        assertMoney(only, "openingPrincipal", "5000.00");
        assertMoney(only, "principal", "5000.00");
        assertMoney(only, "interest", "50.00");
        assertMoney(only, "total", "5050.00");
        assertMoney(only, "closingPrincipal", "0.00");
    }

    @Test
    void dueDatesAreAnchoredToFirstDueDateForMonthEnds() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-004", "C-004", "3000.00", "0", 3,
                                "2026-01-01", "2026-01-31")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode schedule = readJson(result).get("schedule");
        assertThat(schedule.get(0).get("dueDate").asText()).isEqualTo("2026-01-31");
        assertThat(schedule.get(1).get("dueDate").asText()).isEqualTo("2026-02-28");
        assertThat(schedule.get(2).get("dueDate").asText()).isEqualTo("2026-03-31");
    }

    @Test
    void lastPeriodAbsorbsPrincipalRoundingDifference() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-005", "C-005", "10000.00", "0", 3,
                                "2026-01-01", "2026-02-01")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode schedule = readJson(result).get("schedule");
        assertMoney(schedule.get(0), "principal", "3333.33");
        assertMoney(schedule.get(1), "principal", "3333.33");
        assertMoney(schedule.get(2), "principal", "3333.34");
        assertMoney(schedule.get(2), "closingPrincipal", "0.00");

        BigDecimal principalSum = sum(schedule, "principal");
        assertThat(principalSum).isEqualByComparingTo("10000.00");
        assertMoney(readJson(result).get("summary"), "totalPrincipal", "10000.00");

        LeaseContract persisted = contractRepository.findByContractNo("C-005").orElseThrow();
        BigDecimal persistedSum = persisted.getScheduleItems().stream()
                .map(item -> item.getPrincipal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(persistedSum).isEqualByComparingTo("10000.00");
    }

    @Test
    void rejectsFinancingAmountAboveOriginalValue() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asset": {"assetCode": "A-010", "assetName": "设备", "category": "机械", "originalValue": 1000.00},
                                  "contract": {"contractNo": "C-010", "startDate": "2026-01-01", "firstDueDate": "2026-02-01",
                                               "financingAmount": 1000.01, "annualRate": 0.05, "periods": 12}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = readJson(result);
        assertThat(error.get("code").asText()).isEqualTo("FINANCING_EXCEEDS_ORIGINAL_VALUE");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("timestamp").asText()).isNotBlank();
    }

    @Test
    void rejectsInvalidFieldValues() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asset": {"assetCode": "A-011", "assetName": "设备", "category": "机械", "originalValue": 0},
                                  "contract": {"contractNo": "C-011", "startDate": "2026-01-01", "firstDueDate": "2026-02-01",
                                               "financingAmount": -1, "annualRate": 1.5, "periods": 0}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = readJson(result);
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        for (JsonNode fieldError : error.get("errors")) {
            fields.add(fieldError.get("field").asText());
        }
        assertThat(fields).contains("asset.originalValue", "contract.financingAmount",
                "contract.annualRate", "contract.periods");
        assertThat(error.get("errors").get(0).get("reason").asText()).isNotBlank();
    }

    @Test
    void rejectsFirstDueDateBeforeStartDate() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-012", "C-012", "1000.00", "0.05", 12,
                                "2026-02-01", "2026-01-01")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(readJson(result).get("code").asText()).isEqualTo("FIRST_DUE_DATE_BEFORE_START");
    }

    @Test
    void rejectsDuplicateAssetCodeAndContractNo() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-020", "C-020", "1000.00", "0.05", 12,
                                "2026-01-01", "2026-02-01")))
                .andExpect(status().isCreated());

        MvcResult duplicateAsset = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-020", "C-021", "1000.00", "0.05", 12,
                                "2026-01-01", "2026-02-01")))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(readJson(duplicateAsset).get("code").asText()).isEqualTo("DUPLICATE_ASSET_CODE");

        MvcResult duplicateContract = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-022", "C-020", "1000.00", "0.05", 12,
                                "2026-01-01", "2026-02-01")))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(readJson(duplicateContract).get("code").asText()).isEqualTo("DUPLICATE_CONTRACT_NO");

        assertThat(contractRepository.count()).isEqualTo(1);
        assertThat(assetRepository.count()).isEqualTo(1);
    }

    @Test
    void getLeaseReturnsScheduleOrderedByPeriod() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("A-030", "C-030", "12000.00", "0.06", 12,
                                "2026-01-01", "2026-02-01")))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/leases/C-030"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = readJson(result);
        assertThat(body.get("asset").get("assetCode").asText()).isEqualTo("A-030");
        assertThat(body.get("contract").get("contractNo").asText()).isEqualTo("C-030");
        JsonNode schedule = body.get("schedule");
        assertThat(schedule).hasSize(12);
        for (int i = 0; i < 12; i++) {
            assertThat(schedule.get(i).get("periodNo").asInt()).isEqualTo(i + 1);
        }
        assertSummaryEqualsScheduleSum(schedule, body.get("summary"));
    }

    @Test
    void getLeaseReturns404WhenContractNotFound() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/leases/NO-SUCH-CONTRACT"))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode error = readJson(result);
        assertThat(error.get("code").asText()).isEqualTo("LEASE_NOT_FOUND");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("timestamp").asText()).isNotBlank();
    }

    private String requestJson(String assetCode, String contractNo, String financingAmount,
                               String annualRate, int periods, String startDate, String firstDueDate) {
        return """
                {
                  "asset": {"assetCode": "%s", "assetName": "数控机床", "category": "机械设备", "originalValue": 200000.00},
                  "contract": {"contractNo": "%s", "startDate": "%s", "firstDueDate": "%s",
                               "financingAmount": %s, "annualRate": %s, "periods": %d}
                }
                """.formatted(assetCode, contractNo, startDate, firstDueDate, financingAmount, annualRate, periods);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertMoney(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue())
                .as("%s.%s", node.path("periodNo").asInt(), field)
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    private BigDecimal sum(JsonNode schedule, String field) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode item : schedule) {
            sum = sum.add(item.get(field).decimalValue());
        }
        return sum;
    }

    private void assertSummaryEqualsScheduleSum(JsonNode schedule, JsonNode summary) {
        assertThat(summary.get("totalPrincipal").decimalValue())
                .isEqualByComparingTo(sum(schedule, "principal"));
        assertThat(summary.get("totalInterest").decimalValue())
                .isEqualByComparingTo(sum(schedule, "interest"));
        assertThat(summary.get("totalAmount").decimalValue())
                .isEqualByComparingTo(sum(schedule, "total"));
    }
}
