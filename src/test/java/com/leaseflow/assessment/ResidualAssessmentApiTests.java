package com.leaseflow.assessment;

import com.leaseflow.asset.LeasedAssetRepository;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class ResidualAssessmentApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeasedAssetRepository assetRepository;

    @Autowired
    private ResidualAssessmentRepository assessmentRepository;

    private static final String START_DATE = "2025-01-01";

    private void createLease(String assetCode, String contractNo, String originalValue) {
        String body = """
                {
                  "assetCode": "%s",
                  "assetName": "数控机床",
                  "category": "生产设备",
                  "originalValue": %s,
                  "contractNo": "%s",
                  "startDate": "%s",
                  "firstPaymentDate": "2025-02-01",
                  "financingAmount": %s,
                  "nominalAnnualRate": 0,
                  "termMonths": 3
                }
                """.formatted(assetCode, originalValue, contractNo, START_DATE, originalValue);
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

    private MvcResult register(String assetCode, String assessmentNo, String date,
                               String value, String appraiser, int baseVersion) throws Exception {
        String body = """
                {
                  "assessmentNo": "%s",
                  "assessmentDate": "%s",
                  "assessedValue": %s,
                  "appraiser": "%s",
                  "baseVersion": %d
                }
                """.formatted(assessmentNo, date, value, appraiser, baseVersion);
        return mockMvc.perform(post("/api/assets/{assetCode}/assessments", assetCode)
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

    private long persistedCount(String assetCode) {
        Long assetId = assetRepository.findByAssetCode(assetCode).orElseThrow().getId();
        return assessmentRepository.countByAssetId(assetId);
    }

    @Test
    void registersFirstAssessmentAsVersion1() throws Exception {
        createLease("ASSET-601", "HT-601", "150000.00");

        MvcResult result = register("ASSET-601", "EV-601-1", "2025-06-01",
                "120000.00", "中联评估", 0);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        JsonNode body = json(result);
        assertThat(body.get("assessmentNo").asText()).isEqualTo("EV-601-1");
        assertThat(body.get("assetCode").asText()).isEqualTo("ASSET-601");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("assessmentDate").asText()).isEqualTo("2025-06-01");
        assertMoney(body, "assessedValue", "120000.00");
        assertThat(body.get("appraiser").asText()).isEqualTo("中联评估");
        assertMoney(body, "impairmentAmount", "30000.00");
        assertMoney(body, "residualRate", "0.8000");
        assertThat(body.get("latest").asBoolean()).isTrue();

        assertThat(persistedCount("ASSET-601")).isEqualTo(1);
    }

    @Test
    void appendsVersionsAndExposesSortedHistoryWithLatestFlag() throws Exception {
        createLease("ASSET-602", "HT-602", "150000.00");

        assertThat(register("ASSET-602", "EV-602-1", "2025-03-01",
                "140000.00", "中联评估", 0).getResponse().getStatus()).isEqualTo(201);
        MvcResult second = register("ASSET-602", "EV-602-2", "2025-06-01",
                "120000.00", "华信评估", 1);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        JsonNode secondBody = json(second);
        assertThat(secondBody.get("version").asInt()).isEqualTo(2);
        assertMoney(secondBody, "impairmentAmount", "30000.00");
        assertThat(secondBody.get("latest").asBoolean()).isTrue();

        // 历史查询：按版本升序稳定排序，仅最新一条 latest=true
        MvcResult historyResult = mockMvc.perform(
                get("/api/assets/ASSET-602/assessments")).andReturn();
        assertThat(historyResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode history = json(historyResult);
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(history.get(0).get("assessmentNo").asText()).isEqualTo("EV-602-1");
        assertThat(history.get(0).get("latest").asBoolean()).isFalse();
        assertThat(history.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("assessmentNo").asText()).isEqualTo("EV-602-2");
        assertThat(history.get(1).get("latest").asBoolean()).isTrue();

        // 当前版本查询
        MvcResult currentResult = mockMvc.perform(
                get("/api/assets/ASSET-602/assessments/current")).andReturn();
        assertThat(currentResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode current = json(currentResult);
        assertThat(current.get("version").asInt()).isEqualTo(2);
        assertThat(current.get("assessmentNo").asText()).isEqualTo("EV-602-2");
        assertMoney(current, "assessedValue", "120000.00");
        assertThat(current.get("latest").asBoolean()).isTrue();

        // 历史版本不可变：第一次评估记录保持原值
        assertMoney(history.get(0), "assessedValue", "140000.00");
        assertMoney(history.get(0), "impairmentAmount", "10000.00");
    }

    @Test
    void rejectsStaleBaseVersionWith409() throws Exception {
        createLease("ASSET-603", "HT-603", "150000.00");
        assertThat(register("ASSET-603", "EV-603-1", "2025-03-01",
                "140000.00", "中联评估", 0).getResponse().getStatus()).isEqualTo(201);

        // 客户端基于过期版本 0 再次提交
        MvcResult stale = register("ASSET-603", "EV-603-2", "2025-06-01",
                "130000.00", "华信评估", 0);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = json(stale);
        assertThat(error.get("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(error.get("message").asText()).contains("0").contains("1");
        assertThat(error.get("timestamp").asText()).isNotBlank();

        // 超前版本同样拒绝
        MvcResult future = register("ASSET-603", "EV-603-3", "2025-06-01",
                "130000.00", "华信评估", 5);
        assertThat(future.getResponse().getStatus()).isEqualTo(409);

        // 失败请求不留下任何记录，版本链不出现跳号
        assertThat(persistedCount("ASSET-603")).isEqualTo(1);
        MvcResult accepted = register("ASSET-603", "EV-603-4", "2025-06-01",
                "130000.00", "华信评估", 1);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(accepted).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void idempotentReplayReturnsFirstResult() throws Exception {
        createLease("ASSET-604", "HT-604", "150000.00");

        MvcResult first = register("ASSET-604", "EV-604-1", "2025-03-01",
                "140000.00", "中联评估", 0);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 相同编号 + 相同资产、日期、价值、机构：返回首次结果，不新增版本
        MvcResult replay = register("ASSET-604", "EV-604-1", "2025-03-01",
                "140000.00", "中联评估", 0);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = json(replay);
        assertThat(replayBody.get("assessmentNo").asText()).isEqualTo("EV-604-1");
        assertThat(replayBody.get("version").asInt()).isEqualTo(1);
        assertMoney(replayBody, "assessedValue", "140000.00");
        assertThat(replayBody.get("latest").asBoolean()).isTrue();
        assertThat(persistedCount("ASSET-604")).isEqualTo(1);

        // 追加新版本后重放旧编号：仍返回首次结果，latest 标记实时推导为 false
        assertThat(register("ASSET-604", "EV-604-2", "2025-06-01",
                "130000.00", "中联评估", 1).getResponse().getStatus()).isEqualTo(201);
        MvcResult replayAfterNewVersion = register("ASSET-604", "EV-604-1", "2025-03-01",
                "140000.00", "中联评估", 1);
        assertThat(replayAfterNewVersion.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayedOld = json(replayAfterNewVersion);
        assertThat(replayedOld.get("version").asInt()).isEqualTo(1);
        assertThat(replayedOld.get("latest").asBoolean()).isFalse();
        assertThat(persistedCount("ASSET-604")).isEqualTo(2);
    }

    @Test
    void rejectsAssessmentNoReuseWithDifferentContent() throws Exception {
        createLease("ASSET-605", "HT-605", "150000.00");
        createLease("ASSET-606", "HT-606", "80000.00");
        assertThat(register("ASSET-605", "EV-605-1", "2025-03-01",
                "140000.00", "中联评估", 0).getResponse().getStatus()).isEqualTo(201);

        // 同编号不同价值
        MvcResult differentValue = register("ASSET-605", "EV-605-1", "2025-03-01",
                "139000.00", "中联评估", 1);
        assertThat(differentValue.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(differentValue).get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");

        // 同编号不同日期
        assertThat(register("ASSET-605", "EV-605-1", "2025-03-02",
                "140000.00", "中联评估", 1).getResponse().getStatus()).isEqualTo(409);

        // 同编号不同机构
        assertThat(register("ASSET-605", "EV-605-1", "2025-03-01",
                "140000.00", "华信评估", 1).getResponse().getStatus()).isEqualTo(409);

        // 同编号用于其他资产（全局唯一）
        assertThat(register("ASSET-606", "EV-605-1", "2025-03-01",
                "70000.00", "中联评估", 0).getResponse().getStatus()).isEqualTo(409);

        assertThat(persistedCount("ASSET-605")).isEqualTo(1);
        assertThat(persistedCount("ASSET-606")).isZero();
    }

    @Test
    void rejectsAssessmentDateBeforeLeaseStart() throws Exception {
        createLease("ASSET-607", "HT-607", "150000.00");

        MvcResult tooEarly = register("ASSET-607", "EV-607-1", "2024-12-31",
                "140000.00", "中联评估", 0);
        assertThat(tooEarly.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = json(tooEarly);
        assertThat(error.get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(error.get("message").asText()).contains("起租日");

        // 起租日当天允许
        MvcResult onStart = register("ASSET-607", "EV-607-2", START_DATE,
                "140000.00", "中联评估", 0);
        assertThat(onStart.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void rejectsAssessmentDateNotAfterLatest() throws Exception {
        createLease("ASSET-608", "HT-608", "150000.00");
        assertThat(register("ASSET-608", "EV-608-1", "2025-06-01",
                "140000.00", "中联评估", 0).getResponse().getStatus()).isEqualTo(201);

        // 与最新记录同一天
        MvcResult sameDay = register("ASSET-608", "EV-608-2", "2025-06-01",
                "139000.00", "中联评估", 1);
        assertThat(sameDay.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(sameDay).get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");

        // 早于最新记录
        assertThat(register("ASSET-608", "EV-608-3", "2025-05-01",
                "139000.00", "中联评估", 1).getResponse().getStatus()).isEqualTo(400);

        assertThat(persistedCount("ASSET-608")).isEqualTo(1);
    }

    @Test
    void rejectsAssessedValueOutOfRange() throws Exception {
        createLease("ASSET-609", "HT-609", "150000.00");

        // 超过资产原值
        MvcResult tooHigh = register("ASSET-609", "EV-609-1", "2025-03-01",
                "150000.01", "中联评估", 0);
        assertThat(tooHigh.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = json(tooHigh);
        assertThat(error.get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(error.get("message").asText()).contains("原值");

        // 负数（参数校验）
        MvcResult negative = register("ASSET-609", "EV-609-2", "2025-03-01",
                "-1.00", "中联评估", 0);
        assertThat(negative.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(negative).get("code").asText()).isEqualTo("VALIDATION_FAILED");

        // 边界值：0 与原值本身均合法
        MvcResult zero = register("ASSET-609", "EV-609-3", "2025-03-01",
                "0.00", "中联评估", 0);
        assertThat(zero.getResponse().getStatus()).isEqualTo(201);
        JsonNode zeroBody = json(zero);
        assertMoney(zeroBody, "impairmentAmount", "150000.00");
        assertMoney(zeroBody, "residualRate", "0.0000");

        MvcResult full = register("ASSET-609", "EV-609-4", "2025-04-01",
                "150000.00", "中联评估", 1);
        assertThat(full.getResponse().getStatus()).isEqualTo(201);
        JsonNode fullBody = json(full);
        assertMoney(fullBody, "impairmentAmount", "0.00");
        assertMoney(fullBody, "residualRate", "1.0000");
    }

    @Test
    void failedRegistrationLeavesNoGapInVersionChain() throws Exception {
        createLease("ASSET-610", "HT-610", "150000.00");
        assertThat(register("ASSET-610", "EV-610-1", "2025-03-01",
                "140000.00", "中联评估", 0).getResponse().getStatus()).isEqualTo(201);

        // 业务校验失败（超原值）
        assertThat(register("ASSET-610", "EV-610-2", "2025-04-01",
                "200000.00", "中联评估", 1).getResponse().getStatus()).isEqualTo(400);
        // 版本冲突失败
        assertThat(register("ASSET-610", "EV-610-3", "2025-04-01",
                "130000.00", "中联评估", 0).getResponse().getStatus()).isEqualTo(409);

        // 下一次成功登记仍取得版本 2，无跳号、无不完整记录
        MvcResult accepted = register("ASSET-610", "EV-610-4", "2025-04-01",
                "130000.00", "中联评估", 1);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(accepted).get("version").asInt()).isEqualTo(2);
        assertThat(persistedCount("ASSET-610")).isEqualTo(2);
        assertThat(assessmentRepository.findByAssessmentNo("EV-610-2")).isEmpty();
        assertThat(assessmentRepository.findByAssessmentNo("EV-610-3")).isEmpty();
    }

    @Test
    void roundsResidualRateAndImpairmentHalfUp() throws Exception {
        createLease("ASSET-611", "HT-611", "30000.00");

        MvcResult result = register("ASSET-611", "EV-611-1", "2025-03-01",
                "10000.00", "中联评估", 0);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);
        // 10000 / 30000 = 0.33333… → 0.3333（4 位小数 HALF_UP）
        assertMoney(body, "residualRate", "0.3333");
        assertMoney(body, "impairmentAmount", "20000.00");

        MvcResult second = register("ASSET-611", "EV-611-2", "2025-04-01",
                "10000.01", "中联评估", 1);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        // 10000.01 / 30000 = 0.3333336… → 0.3333
        assertMoney(json(second), "residualRate", "0.3333");
        assertMoney(json(second), "impairmentAmount", "19999.99");
    }

    @Test
    void returns404ForUnknownAssetOrMissingAssessment() throws Exception {
        createLease("ASSET-612", "HT-612", "150000.00");

        MvcResult unknownPost = register("NO-SUCH-ASSET", "EV-612-1", "2025-03-01",
                "1000.00", "中联评估", 0);
        assertThat(unknownPost.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(unknownPost).get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");

        assertThat(mockMvc.perform(get("/api/assets/NO-SUCH-ASSET/assessments"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/assets/NO-SUCH-ASSET/assessments/current"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // 无评估记录的租赁物：历史为空数组，当前版本 404
        MvcResult emptyHistory = mockMvc.perform(
                get("/api/assets/ASSET-612/assessments")).andReturn();
        assertThat(emptyHistory.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(emptyHistory).size()).isZero();
        assertThat(mockMvc.perform(get("/api/assets/ASSET-612/assessments/current"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void rejectsMissingOrInvalidFields() throws Exception {
        createLease("ASSET-613", "HT-613", "150000.00");

        String missingFields = """
                {
                  "assessmentNo": ""
                }
                """;
        MvcResult missing = mockMvc.perform(
                        post("/api/assets/ASSET-613/assessments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(missingFields))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = json(missing);
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        error.get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("assessmentNo", "assessmentDate", "assessedValue",
                "appraiser", "baseVersion");

        // 小数位超过 2 位
        MvcResult tooManyDecimals = register("ASSET-613", "EV-613-1", "2025-03-01",
                "100.001", "中联评估", 0);
        assertThat(tooManyDecimals.getResponse().getStatus()).isEqualTo(400);

        assertThat(persistedCount("ASSET-613")).isZero();
    }

    @Test
    void concurrentRegistrationsOnSameAssetAcceptOnlyOne() throws Exception {
        createLease("ASSET-614", "HT-614", "150000.00");

        // 两个并发请求基于同一当前版本 0 提交：悲观锁串行化后仅一个成功
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<MvcResult> first = executor.submit(() -> {
                ready.countDown();
                go.await();
                return register("ASSET-614", "EV-614-A", "2025-03-01",
                        "140000.00", "中联评估", 0);
            });
            Future<MvcResult> second = executor.submit(() -> {
                ready.countDown();
                go.await();
                return register("ASSET-614", "EV-614-B", "2025-03-02",
                        "139000.00", "华信评估", 0);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            statuses.add(first.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            statuses.add(second.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);

            // 只落库一条记录，版本链为 1，无跳号
            assertThat(persistedCount("ASSET-614")).isEqualTo(1);
            MvcResult history = mockMvc.perform(
                    get("/api/assets/ASSET-614/assessments")).andReturn();
            JsonNode items = json(history);
            assertThat(items.size()).isEqualTo(1);
            assertThat(items.get(0).get("version").asInt()).isEqualTo(1);
            assertThat(items.get(0).get("latest").asBoolean()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
