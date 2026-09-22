package com.leaseflow.collection;

import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.payment.RentPaymentRepository;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class CollectionTaskApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CollectionTaskRepository taskRepository;

    @Autowired
    private RentPaymentRepository paymentRepository;

    @Autowired
    private PaymentScheduleItemRepository scheduleItemRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private LeasedAssetRepository assetRepository;

    private static final String START_DATE = "2025-01-01";
    private static final String FIRST_PAYMENT_DATE = "2025-02-01";

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        paymentRepository.deleteAll();
        scheduleItemRepository.deleteAll();
        contractRepository.deleteAll();
        assetRepository.deleteAll();
    }

    private void createLease(String assetCode, String contractNo) {
        String body = """
                {
                  "assetCode": "%s",
                  "assetName": "数控机床",
                  "category": "生产设备",
                  "originalValue": 6000.00,
                  "contractNo": "%s",
                  "startDate": "%s",
                  "firstPaymentDate": "%s",
                  "financingAmount": 6000.00,
                  "nominalAnnualRate": 0,
                  "termMonths": 3
                }
                """.formatted(assetCode, contractNo, START_DATE, FIRST_PAYMENT_DATE);
        try {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(result ->
                            assertThat(result.getResponse().getStatus()).isEqualTo(201));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void registerPayment(String contractNo, int periodNo, String paymentNo,
                                 String amount) {
        String body = """
                {
                  "paymentNo": "%s",
                  "amount": %s,
                  "paymentDate": "2025-02-05"
                }
                """.formatted(paymentNo, amount);
        try {
            mockMvc.perform(post(
                                    "/api/leases/{contractNo}/schedule/{periodNo}/payments",
                                    contractNo, periodNo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(result ->
                            assertThat(result.getResponse().getStatus()).isEqualTo(201));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult scan(String businessDate) throws Exception {
        return mockMvc.perform(post("/api/collection-tasks/scan")
                        .param("businessDate", businessDate))
                .andReturn();
    }

    private MvcResult queryTasks(String contractNo, String status) throws Exception {
        var builder = get("/api/collection-tasks");
        if (contractNo != null) {
            builder = builder.param("contractNo", contractNo);
        }
        if (status != null) {
            builder = builder.param("status", status);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void scanCreatesTasksForOverdueUnpaidPeriods() throws Exception {
        createLease("ASSET-501", "HT-501");

        MvcResult result = scan("2025-02-10");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.get("businessDate").asText()).isEqualTo("2025-02-10");
        assertThat(body.get("createdCount").asInt()).isEqualTo(1);
        assertThat(body.get("updatedCount").asInt()).isZero();
        assertThat(body.get("closedCount").asInt()).isZero();

        JsonNode tasks = json(queryTasks("HT-501", null));
        assertThat(tasks).hasSize(1);
        JsonNode task = tasks.get(0);
        assertThat(task.get("contractNo").asText()).isEqualTo("HT-501");
        assertThat(task.get("periodNo").asInt()).isEqualTo(1);
        assertThat(task.get("dueDate").asText()).isEqualTo("2025-02-01");
        assertThat(task.get("overdueDays").asInt()).isEqualTo(9);
        assertThat(task.get("outstandingAmount").decimalValue())
                .isEqualByComparingTo("2000.00");
        assertThat(task.get("status").asText()).isEqualTo("OPEN");

        // 每个期次只有一条催收任务
        assertThat(taskRepository.count()).isEqualTo(1);

        // 到期日当天不算逾期
        createLease("ASSET-502", "HT-502");
        MvcResult boundary = scan("2025-02-01");
        assertThat(json(boundary).get("createdCount").asInt()).isZero();
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void partialPaymentCollectsRemainingOutstanding() throws Exception {
        createLease("ASSET-503", "HT-503");
        registerPayment("HT-503", 1, "PAY-503-1", "500.00");

        JsonNode first = json(scan("2025-02-10"));
        assertThat(first.get("createdCount").asInt()).isEqualTo(1);
        JsonNode task = json(queryTasks("HT-503", "OPEN")).get(0);
        assertThat(task.get("outstandingAmount").decimalValue())
                .isEqualByComparingTo("1500.00");
        assertThat(task.get("overdueDays").asInt()).isEqualTo(9);

        // 再次回款后重新扫描：刷新未收金额与逾期天数，不新增任务
        registerPayment("HT-503", 1, "PAY-503-2", "200.00");
        JsonNode second = json(scan("2025-02-15"));
        assertThat(second.get("createdCount").asInt()).isZero();
        assertThat(second.get("updatedCount").asInt()).isEqualTo(1);
        assertThat(second.get("closedCount").asInt()).isZero();

        JsonNode refreshed = json(queryTasks("HT-503", null)).get(0);
        assertThat(refreshed.get("outstandingAmount").decimalValue())
                .isEqualByComparingTo("1300.00");
        assertThat(refreshed.get("overdueDays").asInt()).isEqualTo(14);
        assertThat(refreshed.get("status").asText()).isEqualTo("OPEN");
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void fullPaymentClosesTaskOnNextScan() throws Exception {
        createLease("ASSET-504", "HT-504");
        JsonNode created = json(scan("2025-02-10"));
        assertThat(created.get("createdCount").asInt()).isEqualTo(1);

        // 足额回款后下一次扫描关闭任务
        registerPayment("HT-504", 1, "PAY-504-1", "2000.00");
        JsonNode closing = json(scan("2025-02-11"));
        assertThat(closing.get("createdCount").asInt()).isZero();
        assertThat(closing.get("updatedCount").asInt()).isZero();
        assertThat(closing.get("closedCount").asInt()).isEqualTo(1);

        JsonNode closed = json(queryTasks("HT-504", "CLOSED")).get(0);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("overdueDays").asInt()).isEqualTo(9);
        assertThat(closed.get("outstandingAmount").decimalValue())
                .isEqualByComparingTo("0.00");

        // 已足额回款的期次不重新生成任务；CLOSED 任务再次扫描保持不变
        JsonNode again = json(scan("2025-03-02"));
        assertThat(again.get("createdCount").asInt()).isEqualTo(1);
        assertThat(again.get("updatedCount").asInt()).isZero();
        assertThat(again.get("closedCount").asInt()).isZero();

        List<JsonNode> all = new ArrayList<>();
        json(queryTasks("HT-504", null)).forEach(all::add);
        assertThat(all).hasSize(2);
        assertThat(all).extracting(node -> node.get("status").asText())
                .containsExactly("CLOSED", "OPEN");
    }

    @Test
    void repeatedScanOnSameDateIsIdempotent() throws Exception {
        createLease("ASSET-505", "HT-505");

        JsonNode first = json(scan("2025-04-15"));
        assertThat(first.get("createdCount").asInt()).isEqualTo(3);

        JsonNode second = json(scan("2025-04-15"));
        assertThat(second.get("createdCount").asInt()).isZero();
        assertThat(second.get("updatedCount").asInt()).isZero();
        assertThat(second.get("closedCount").asInt()).isZero();

        JsonNode third = json(scan("2025-04-15"));
        assertThat(third.get("createdCount").asInt()).isZero();
        assertThat(taskRepository.count()).isEqualTo(3);
    }

    @Test
    void concurrentScansDoNotCreateDuplicates() throws Exception {
        createLease("ASSET-506", "HT-506");

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                MvcResult result = scan("2025-03-10");
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                return json(result).get("createdCount").asInt();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int totalCreated = 0;
        for (Future<Integer> future : futures) {
            totalCreated += future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // 2 个逾期期次（2025-02-01、2025-03-01），并发下只能创建 2 条
        assertThat(totalCreated).isEqualTo(2);
        assertThat(taskRepository.count()).isEqualTo(2);
    }

    @Test
    void queryFiltersByContractAndStatusAndSortsStably() throws Exception {
        createLease("ASSET-507", "HT-507");
        createLease("ASSET-508", "HT-508");

        // 首次扫描为两份合同的期次 1 各生成一条 OPEN
        assertThat(json(scan("2025-02-10")).get("createdCount").asInt()).isEqualTo(2);

        // HT-507 期次 1 足额回款，下次扫描时 OPEN -> CLOSED
        registerPayment("HT-507", 1, "PAY-507-1", "2000.00");
        JsonNode laterScan = json(scan("2025-04-10"));
        assertThat(laterScan.get("createdCount").asInt()).isEqualTo(4);
        assertThat(laterScan.get("closedCount").asInt()).isEqualTo(1);

        // 无筛选：按到期日、合同编号、期次排序
        JsonNode all = json(queryTasks(null, null));
        List<String> keys = new ArrayList<>();
        all.forEach(node -> keys.add(node.get("dueDate").asText()
                + "|" + node.get("contractNo").asText()
                + "|" + node.get("periodNo").asInt()));
        List<String> sortedKeys = new ArrayList<>(keys);
        sortedKeys.sort(null);
        assertThat(keys).containsExactlyElementsOf(sortedKeys);
        assertThat(keys).hasSize(6);

        // 按合同筛选
        JsonNode only507 = json(queryTasks("HT-507", null));
        assertThat(only507).hasSize(3);
        assertThat(only507).allSatisfy(node ->
                assertThat(node.get("contractNo").asText()).isEqualTo("HT-507"));

        // 按合同 + 状态筛选
        JsonNode open507 = json(queryTasks("HT-507", "OPEN"));
        assertThat(open507).hasSize(2);
        assertThat(open507).allSatisfy(node ->
                assertThat(node.get("status").asText()).isEqualTo("OPEN"));
        JsonNode closed507 = json(queryTasks("HT-507", "CLOSED"));
        assertThat(closed507).hasSize(1);

        // 仅按状态筛选，跨合同按到期日、合同编号、期次排序
        JsonNode allClosed = json(queryTasks(null, "CLOSED"));
        assertThat(allClosed).hasSize(1);
        assertThat(allClosed.get(0).get("contractNo").asText()).isEqualTo("HT-507");

        JsonNode allOpen = json(queryTasks(null, "OPEN"));
        assertThat(allOpen).hasSize(5);
        List<String> openKeys = new ArrayList<>();
        allOpen.forEach(node -> openKeys.add(node.get("dueDate").asText()
                + "|" + node.get("contractNo").asText()
                + "|" + node.get("periodNo").asInt()));
        List<String> sortedOpenKeys = new ArrayList<>(openKeys);
        sortedOpenKeys.sort(null);
        assertThat(openKeys).containsExactlyElementsOf(sortedOpenKeys);

        // 非法状态返回 400
        MvcResult invalid = mockMvc.perform(get("/api/collection-tasks")
                        .param("status", "DONE"))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = json(invalid);
        assertThat(error.get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(error.get("message").asText()).contains("DONE");
    }

    @Test
    void scanRejectsMissingOrMalformedBusinessDate() throws Exception {
        MvcResult missing = mockMvc.perform(post("/api/collection-tasks/scan")).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(missing).get("code").asText()).isEqualTo("VALIDATION_FAILED");

        MvcResult malformed = mockMvc.perform(post("/api/collection-tasks/scan")
                        .param("businessDate", "2025/02/10"))
                .andReturn();
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(malformed).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }
}
