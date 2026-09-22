package com.leaseflow.collection;

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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private PaymentScheduleItemRepository scheduleItemRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private RentPaymentRepository paymentRepository;

    private static final String START_DATE = "2025-01-01";

    @BeforeEach
    void clearData() {
        taskRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        scheduleItemRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
    }

    private void createLease(String assetCode, String contractNo, String firstPaymentDate,
                             int termMonths) {
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
                  "termMonths": %d
                }
                """.formatted(assetCode, contractNo, START_DATE, firstPaymentDate, termMonths);
        try {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .isEqualTo(201));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void registerPayment(String contractNo, int periodNo, String paymentNo,
                                 String amount, String paymentDate) {
        String body = """
                {
                  "paymentNo": "%s",
                  "amount": %s,
                  "paymentDate": "%s"
                }
                """.formatted(paymentNo, amount, paymentDate);
        try {
            mockMvc.perform(post(
                                    "/api/leases/{contractNo}/schedule/{periodNo}/payments",
                                    contractNo, periodNo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .isEqualTo(201));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult scan(String businessDate) throws Exception {
        return mockMvc.perform(post("/api/collection-tasks/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDate": "%s"}
                                """.formatted(businessDate)))
                .andReturn();
    }

    private MvcResult queryTasks(String contractNo, String status) throws Exception {
        String url = "/api/collection-tasks";
        List<String> params = new ArrayList<>();
        if (contractNo != null) {
            params.add("contractNo=" + contractNo);
        }
        if (status != null) {
            params.add("status=" + status);
        }
        if (!params.isEmpty()) {
            url += "?" + String.join("&", params);
        }
        return mockMvc.perform(get(url)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static void assertTask(JsonNode task, String contractNo, int periodNo,
                                   String dueDate, int overdueDays, String outstanding,
                                   String status) {
        assertThat(task.get("contractNo").asText()).isEqualTo(contractNo);
        assertThat(task.get("periodNo").asInt()).isEqualTo(periodNo);
        assertThat(task.get("dueDate").asText()).isEqualTo(dueDate);
        assertThat(task.get("overdueDays").asInt()).isEqualTo(overdueDays);
        assertThat(task.get("outstandingAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal(outstanding));
        assertThat(task.get("status").asText()).isEqualTo(status);
    }

    @Test
    void scanCreatesTasksOnlyWhenDueDateStrictlyBeforeBusinessDate() throws Exception {
        createLease("A-501", "HT-501", "2025-02-01", 3);

        // 业务日期等于到期日：不算逾期
        MvcResult onDueDate = scan("2025-02-01");
        assertThat(onDueDate.getResponse().getStatus()).isEqualTo(200);
        JsonNode onDue = json(onDueDate);
        assertThat(onDue.get("businessDate").asText()).isEqualTo("2025-02-01");
        assertThat(onDue.get("createdCount").asInt()).isZero();
        assertThat(onDue.get("updatedCount").asInt()).isZero();
        assertThat(onDue.get("closedCount").asInt()).isZero();
        assertThat(onDue.get("tasks").size()).isZero();
        assertThat(taskRepository.count()).isZero();

        // 业务日期晚于到期日 1 天：生成 OPEN 催收任务，逾期天数为自然日差
        MvcResult overdue = scan("2025-02-02");
        assertThat(overdue.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(overdue);
        assertThat(body.get("createdCount").asInt()).isEqualTo(1);
        assertThat(body.get("updatedCount").asInt()).isZero();
        assertThat(body.get("closedCount").asInt()).isZero();
        assertTask(body.get("tasks").get(0), "HT-501", 1, "2025-02-01", 1,
                "2000.00", "OPEN");

        // 任务已持久化，且每个期次只有一条
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void rescanRefreshesPartialPaymentAndClosesFullyPaidPeriod() throws Exception {
        createLease("A-502", "HT-502", "2025-02-01", 3);

        JsonNode first = json(scan("2025-04-15"));
        assertThat(first.get("createdCount").asInt()).isEqualTo(3);
        // 2025 年 2/1→4/15 为 73 天，3/1→4/15 为 45 天，4/1→4/15 为 14 天
        assertTask(first.get("tasks").get(0), "HT-502", 1, "2025-02-01", 73,
                "2000.00", "OPEN");
        assertTask(first.get("tasks").get(1), "HT-502", 2, "2025-03-01", 45,
                "2000.00", "OPEN");
        assertTask(first.get("tasks").get(2), "HT-502", 3, "2025-04-01", 14,
                "2000.00", "OPEN");

        // 第 1 期部分回款 1500（剩 500 未收），第 2 期足额回款
        registerPayment("HT-502", 1, "PAY-502-1", "1500.00", "2025-04-10");
        registerPayment("HT-502", 2, "PAY-502-2", "2000.00", "2025-04-11");

        JsonNode second = json(scan("2025-04-15"));
        assertThat(second.get("createdCount").asInt()).isZero();
        assertThat(second.get("updatedCount").asInt()).isEqualTo(1);
        assertThat(second.get("closedCount").asInt()).isEqualTo(1);
        assertThat(second.get("tasks").size()).isEqualTo(3);

        // 部分回款：按剩余未收金额催收，仍为 OPEN
        assertTask(second.get("tasks").get(0), "HT-502", 1, "2025-02-01", 73,
                "500.00", "OPEN");
        // 足额回款：任务关闭，未收金额为 0
        assertTask(second.get("tasks").get(1), "HT-502", 2, "2025-03-01", 45,
                "0.00", "CLOSED");
        // 未变更的 OPEN 任务仍返回，但不计入新增/更新/关闭
        assertTask(second.get("tasks").get(2), "HT-502", 3, "2025-04-01", 14,
                "2000.00", "OPEN");

        // 每个期次仍只有一条任务，无重复数据
        assertThat(taskRepository.count()).isEqualTo(3);
    }

    @Test
    void fullyPaidPeriodNeverCreatesTaskAndLaterPaymentClosesTask() throws Exception {
        createLease("A-503", "HT-503", "2025-02-01", 3);

        // 首次扫描前第 1 期已足额回款：不得生成催收任务
        registerPayment("HT-503", 1, "PAY-503-1", "2000.00", "2025-02-05");

        JsonNode first = json(scan("2025-04-15"));
        assertThat(first.get("createdCount").asInt()).isEqualTo(2);
        assertThat(first.get("closedCount").asInt()).isZero();
        assertThat(first.get("tasks").size()).isEqualTo(2);
        assertTask(first.get("tasks").get(0), "HT-503", 2, "2025-03-01", 45,
                "2000.00", "OPEN");
        assertTask(first.get("tasks").get(1), "HT-503", 3, "2025-04-01", 14,
                "2000.00", "OPEN");
        assertThat(taskRepository.count()).isEqualTo(2);

        // 业务日期推进：仅刷新逾期天数（2 条更新），不新增不关闭
        JsonNode later = json(scan("2025-05-15"));
        assertThat(later.get("createdCount").asInt()).isZero();
        assertThat(later.get("updatedCount").asInt()).isEqualTo(2);
        assertThat(later.get("closedCount").asInt()).isZero();
        assertTask(later.get("tasks").get(0), "HT-503", 2, "2025-03-01", 75,
                "2000.00", "OPEN");
        assertTask(later.get("tasks").get(1), "HT-503", 3, "2025-04-01", 44,
                "2000.00", "OPEN");

        // 第 3 期足额回款后再次扫描：任务变为 CLOSED
        registerPayment("HT-503", 3, "PAY-503-3", "2000.00", "2025-05-10");
        JsonNode closed = json(scan("2025-05-15"));
        assertThat(closed.get("createdCount").asInt()).isZero();
        assertThat(closed.get("updatedCount").asInt()).isZero();
        assertThat(closed.get("closedCount").asInt()).isEqualTo(1);
        assertTask(closed.get("tasks").get(1), "HT-503", 3, "2025-04-01", 44,
                "0.00", "CLOSED");

        // 已关闭任务不会重复关闭：下一次扫描各项计数均为 0
        JsonNode again = json(scan("2025-05-15"));
        assertThat(again.get("createdCount").asInt()).isZero();
        assertThat(again.get("updatedCount").asInt()).isZero();
        assertThat(again.get("closedCount").asInt()).isZero();
        assertThat(taskRepository.count()).isEqualTo(2);
    }

    @Test
    void repeatedScanIsIdempotent() throws Exception {
        createLease("A-504", "HT-504", "2025-02-01", 3);

        JsonNode first = json(scan("2025-04-15"));
        assertThat(first.get("createdCount").asInt()).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            JsonNode repeated = json(scan("2025-04-15"));
            assertThat(repeated.get("createdCount").asInt()).isZero();
            assertThat(repeated.get("updatedCount").asInt()).isZero();
            assertThat(repeated.get("closedCount").asInt()).isZero();
            assertThat(repeated.get("tasks").size()).isEqualTo(3);
        }
        assertThat(taskRepository.count()).isEqualTo(3);
    }

    @Test
    void concurrentScansCreateNoDuplicateTasks() throws Exception {
        createLease("A-505", "HT-505", "2025-02-01", 3);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong totalCreated = new AtomicLong();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    MvcResult result = scan("2025-04-15");
                    if (result.getResponse().getStatus() != 200) {
                        synchronized (failures) {
                            failures.add("status=" + result.getResponse().getStatus());
                        }
                    } else {
                        totalCreated.addAndGet(json(result).get("createdCount").asLong());
                    }
                } catch (Exception ex) {
                    synchronized (failures) {
                        failures.add(ex.toString());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(failures).isEmpty();
        // 全部并发请求合计只新增 3 条任务
        assertThat(totalCreated.get()).isEqualTo(3);
        assertThat(taskRepository.count()).isEqualTo(3);

        // 每个租金期次恰好一条任务（唯一约束 + 悲观锁双重保证）
        List<Long> scheduleItemIds = taskRepository.findAll().stream()
                .map(task -> task.getScheduleItem().getId())
                .toList();
        assertThat(scheduleItemIds).doesNotHaveDuplicates();

        // 并发结束后再扫描：幂等，无新增
        JsonNode after = json(scan("2025-04-15"));
        assertThat(after.get("createdCount").asInt()).isZero();
        assertThat(after.get("updatedCount").asInt()).isZero();
        assertThat(after.get("closedCount").asInt()).isZero();
    }

    @Test
    void queryFiltersByContractAndStatusWithStableOrdering() throws Exception {
        // 两合同各有 3 期且到期日相同，用于验证到期日相同时按合同编号、期次排序
        createLease("A-506", "HT-506", "2025-02-01", 3);
        createLease("A-507", "HT-507", "2025-02-01", 3);

        // 首次扫描生成全部 6 条 OPEN 任务
        JsonNode scan = json(scan("2025-04-15"));
        assertThat(scan.get("createdCount").asInt()).isEqualTo(6);

        // HT-506 第 2 期足额回款后再次扫描，任务关闭；其余保持 OPEN
        registerPayment("HT-506", 2, "PAY-506-2", "2000.00", "2025-03-05");
        JsonNode rescanned = json(scan("2025-04-15"));
        assertThat(rescanned.get("closedCount").asInt()).isEqualTo(1);

        // 全量查询：按到期日、合同编号、期次稳定排序
        JsonNode allTasks = json(queryTasks(null, null));
        assertThat(allTasks.size()).isEqualTo(6);
        assertTask(allTasks.get(0), "HT-506", 1, "2025-02-01", 73, "2000.00", "OPEN");
        assertTask(allTasks.get(1), "HT-507", 1, "2025-02-01", 73, "2000.00", "OPEN");
        assertTask(allTasks.get(2), "HT-506", 2, "2025-03-01", 45, "0.00", "CLOSED");
        assertTask(allTasks.get(3), "HT-507", 2, "2025-03-01", 45, "2000.00", "OPEN");
        assertTask(allTasks.get(4), "HT-506", 3, "2025-04-01", 14, "2000.00", "OPEN");
        assertTask(allTasks.get(5), "HT-507", 3, "2025-04-01", 14, "2000.00", "OPEN");

        // 按合同编号筛选
        JsonNode byContract = json(queryTasks("HT-506", null));
        assertThat(byContract.size()).isEqualTo(3);
        for (JsonNode task : byContract) {
            assertThat(task.get("contractNo").asText()).isEqualTo("HT-506");
        }
        assertTask(byContract.get(1), "HT-506", 2, "2025-03-01", 45, "0.00", "CLOSED");

        // 按状态筛选
        JsonNode closedOnly = json(queryTasks(null, "CLOSED"));
        assertThat(closedOnly.size()).isEqualTo(1);
        assertTask(closedOnly.get(0), "HT-506", 2, "2025-03-01", 45, "0.00", "CLOSED");

        JsonNode openOnly = json(queryTasks(null, "OPEN"));
        assertThat(openOnly.size()).isEqualTo(5);

        // 合同编号与状态组合筛选
        JsonNode openOfContract = json(queryTasks("HT-507", "OPEN"));
        assertThat(openOfContract.size()).isEqualTo(3);
        for (JsonNode task : openOfContract) {
            assertThat(task.get("contractNo").asText()).isEqualTo("HT-507");
            assertThat(task.get("status").asText()).isEqualTo("OPEN");
        }

        // 无匹配结果返回空数组
        assertThat(json(queryTasks("NO-SUCH-CONTRACT", null)).size()).isZero();
        assertThat(json(queryTasks("HT-506", "OPEN")).size()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidScanRequestAndStatusFilter() throws Exception {
        MvcResult missingDate = mockMvc.perform(post("/api/collection-tasks/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(missingDate.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = json(missingDate);
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        error.get("errors").forEach(node -> fields.add(node.get("field").asText()));
        assertThat(fields).contains("businessDate");

        // 非法状态枚举返回 400 并提示允许值
        MvcResult badStatus = queryTasks(null, "DONE");
        assertThat(badStatus.getResponse().getStatus()).isEqualTo(400);
        JsonNode statusError = json(badStatus);
        assertThat(statusError.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(statusError.get("message").asText()).isNotBlank();
        assertThat(statusError.get("errors").get(0).get("reason").asText())
                .contains("OPEN", "CLOSED");

        // 校验失败不产生任何任务
        assertThat(taskRepository.count()).isZero();
    }
}
