package com.leaseflow.valuation;

import com.leaseflow.contract.LeaseContractRepository;
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
class AssetValuationApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssetValuationRepository valuationRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    private void createLease(String assetCode, String contractNo, String originalValue) {
        String body = """
                {
                  "assetCode": "%s",
                  "assetName": "数控机床",
                  "category": "生产设备",
                  "originalValue": %s,
                  "contractNo": "%s",
                  "startDate": "2025-01-01",
                  "firstPaymentDate": "2025-02-01",
                  "financingAmount": %s,
                  "nominalAnnualRate": 0.12,
                  "termMonths": 12
                }
                """.formatted(assetCode, originalValue, contractNo, originalValue);
        try {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult register(String assetCode, String valuationNo, int expectedVersion,
                               String valuationDate, String residualValue,
                               String institution) throws Exception {
        String body = """
                {
                  "valuationNo": "%s",
                  "expectedVersion": %d,
                  "valuationDate": "%s",
                  "residualValue": %s,
                  "institution": "%s"
                }
                """.formatted(valuationNo, expectedVersion, valuationDate, residualValue,
                institution);
        return mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
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

    private long valuationCount(String assetCode) {
        var contract = contractRepository.findByContractNo(assetCode.replace("ASSET", "HT"))
                .orElse(null);
        if (contract == null) {
            return 0;
        }
        return valuationRepository
                .findByAssetIdOrderByVersionNoAscIdAsc(contract.getAsset().getId())
                .size();
    }

    @Test
    void appendsVersionsAndComputesImpairmentAndRate() throws Exception {
        createLease("ASSET-601", "HT-601", "150000.00");

        MvcResult first = register("ASSET-601", "VAL-601-1", 0,
                "2025-03-01", "120000.00", "评估机构甲");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode v1 = json(first);
        assertThat(v1.get("valuationNo").asText()).isEqualTo("VAL-601-1");
        assertThat(v1.get("assetCode").asText()).isEqualTo("ASSET-601");
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.get("valuationDate").asText()).isEqualTo("2025-03-01");
        assertMoney(v1, "residualValue", "120000.00");
        assertMoney(v1, "impairmentAmount", "30000.00");
        assertThat(v1.get("residualRate").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.800000"));
        assertThat(v1.get("institution").asText()).isEqualTo("评估机构甲");
        assertThat(v1.get("latest").asBoolean()).isTrue();

        MvcResult second = register("ASSET-601", "VAL-601-2", 1,
                "2025-06-01", "90000", "评估机构甲");
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        JsonNode v2 = json(second);
        assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
        assertMoney(v2, "residualValue", "90000.00");
        assertMoney(v2, "impairmentAmount", "60000.00");
        assertThat(v2.get("residualRate").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.600000"));
        assertThat(v2.get("latest").asBoolean()).isTrue();

        MvcResult historyResult = mockMvc.perform(
                get("/api/assets/ASSET-601/valuations")).andReturn();
        assertThat(historyResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode history = json(historyResult);
        assertThat(history.get("assetCode").asText()).isEqualTo("ASSET-601");
        assertThat(history.get("latestVersion").asInt()).isEqualTo(2);
        JsonNode versions = history.get("valuations");
        assertThat(versions.size()).isEqualTo(2);
        // 稳定排序：版本号升序
        assertThat(versions.get(0).get("versionNo").asInt()).isEqualTo(1);
        assertThat(versions.get(1).get("versionNo").asInt()).isEqualTo(2);
        assertThat(versions.get(0).get("latest").asBoolean()).isFalse();
        assertThat(versions.get(1).get("latest").asBoolean()).isTrue();

        MvcResult currentResult = mockMvc.perform(
                get("/api/assets/ASSET-601/valuations/current")).andReturn();
        assertThat(currentResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode current = json(currentResult);
        assertThat(current.get("versionNo").asInt()).isEqualTo(2);
        assertThat(current.get("valuationNo").asText()).isEqualTo("VAL-601-2");
        assertThat(current.get("latest").asBoolean()).isTrue();
    }

    @Test
    void rejectsStaleExpectedVersionWith409() throws Exception {
        createLease("ASSET-602", "HT-602", "100000.00");

        assertThat(register("ASSET-602", "VAL-602-1", 0, "2025-02-01", "90000.00", "机构")
                .getResponse().getStatus()).isEqualTo(201);

        // 基于过期版本 0 追加
        MvcResult stale = register("ASSET-602", "VAL-602-STALE", 0,
                "2025-03-01", "80000.00", "机构");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonNode staleError = json(stale);
        assertThat(staleError.get("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(staleError.get("message").asText()).isNotBlank();
        assertThat(staleError.get("message").asText()).contains("1");

        // 跳号的未来版本同样拒绝
        MvcResult future = register("ASSET-602", "VAL-602-FUTURE", 9,
                "2025-03-01", "80000.00", "机构");
        assertThat(future.getResponse().getStatus()).isEqualTo(409);

        // 冲突失败不产生新版本、不留记录
        assertThat(valuationCount("ASSET-602")).isEqualTo(1);
        assertThat(valuationRepository.existsByValuationNo("VAL-602-STALE")).isFalse();
        assertThat(valuationRepository.existsByValuationNo("VAL-602-FUTURE")).isFalse();

        // 基于最新版本 1 仍可追加，版本号连续不跳号
        MvcResult next = register("ASSET-602", "VAL-602-2", 1,
                "2025-03-01", "80000.00", "机构");
        assertThat(next.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(next).get("versionNo").asInt()).isEqualTo(2);
        assertThat(valuationCount("ASSET-602")).isEqualTo(2);
    }

    @Test
    void rejectsValuationDateNotAfterLatestWith409() throws Exception {
        createLease("ASSET-603", "HT-603", "100000.00");

        assertThat(register("ASSET-603", "VAL-603-1", 0, "2025-03-01", "90000.00", "机构")
                .getResponse().getStatus()).isEqualTo(201);

        // 同一天不允许
        MvcResult sameDay = register("ASSET-603", "VAL-603-SAME", 1,
                "2025-03-01", "85000.00", "机构");
        assertThat(sameDay.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(sameDay).get("code").asText()).isEqualTo("VERSION_CONFLICT");

        // 更早日期不允许
        MvcResult earlier = register("ASSET-603", "VAL-603-EARLY", 1,
                "2025-02-01", "85000.00", "机构");
        assertThat(earlier.getResponse().getStatus()).isEqualTo(409);

        assertThat(valuationCount("ASSET-603")).isEqualTo(1);

        // 更晚日期追加成功，版本连续
        MvcResult later = register("ASSET-603", "VAL-603-2", 1,
                "2025-03-02", "85000.00", "机构");
        assertThat(later.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(later).get("versionNo").asInt()).isEqualTo(2);
    }

    @Test
    void idempotentResubmitReturnsFirstResult() throws Exception {
        createLease("ASSET-604", "HT-604", "100000.00");

        MvcResult first = register("ASSET-604", "VAL-604-DUP", 0,
                "2025-03-01", "80000.00", "评估机构乙");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = json(first);
        assertThat(firstBody.get("versionNo").asInt()).isEqualTo(1);

        // 完全相同的编号、资产、日期、价值、机构重复提交：返回首次结果，不新增版本
        MvcResult retry = register("ASSET-604", "VAL-604-DUP", 0,
                "2025-03-01", "80000.00", "评估机构乙");
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        JsonNode retryBody = json(retry);
        assertThat(retryBody.get("versionNo").asInt()).isEqualTo(1);
        assertThat(retryBody.get("valuationNo").asText()).isEqualTo("VAL-604-DUP");
        assertMoney(retryBody, "residualValue", "80000.00");
        assertMoney(retryBody, "impairmentAmount", "20000.00");
        assertThat(retryBody.get("valuationDate").asText()).isEqualTo("2025-03-01");
        assertThat(retryBody.get("institution").asText()).isEqualTo("评估机构乙");
        assertThat(valuationCount("ASSET-604")).isEqualTo(1);

        // 再追加一个新版本后，以旧编号旧内容重试仍返回首次结果
        assertThat(register("ASSET-604", "VAL-604-2", 1, "2025-04-01", "70000.00", "机构")
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult retryAgain = register("ASSET-604", "VAL-604-DUP", 1,
                "2025-03-01", "80000.00", "评估机构乙");
        assertThat(retryAgain.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(retryAgain).get("versionNo").asInt()).isEqualTo(1);
        assertThat(valuationCount("ASSET-604")).isEqualTo(2);
    }

    @Test
    void reusedValuationNoWithDifferentContentReturns409() throws Exception {
        createLease("ASSET-605", "HT-605", "100000.00");
        createLease("ASSET-606", "HT-606", "100000.00");

        assertThat(register("ASSET-605", "VAL-SHARED", 0, "2025-03-01", "80000.00", "机构甲")
                .getResponse().getStatus()).isEqualTo(201);

        // 同资产、不同价值
        assertThat(register("ASSET-605", "VAL-SHARED", 1, "2025-04-01", "70000.00", "机构甲")
                .getResponse().getStatus()).isEqualTo(409);
        // 同资产、不同日期
        assertThat(register("ASSET-605", "VAL-SHARED", 1, "2025-05-01", "80000.00", "机构甲")
                .getResponse().getStatus()).isEqualTo(409);
        // 同资产、不同机构
        assertThat(register("ASSET-605", "VAL-SHARED", 1, "2025-04-01", "80000.00", "机构丙")
                .getResponse().getStatus()).isEqualTo(409);
        // 编号复用发生在其他资产上同样冲突（编号全局唯一）
        MvcResult otherAsset = register("ASSET-606", "VAL-SHARED", 0,
                "2025-03-01", "80000.00", "机构甲");
        assertThat(otherAsset.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(otherAsset).get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");

        assertThat(valuationCount("ASSET-605")).isEqualTo(1);
        assertThat(valuationCount("ASSET-606")).isZero();
    }

    @Test
    void enforcesDateAndValueBusinessRules() throws Exception {
        createLease("ASSET-607", "HT-607", "100000.00");

        // 评估日期早于起租日
        MvcResult beforeStart = register("ASSET-607", "VAL-607-EARLY", 0,
                "2024-12-31", "90000.00", "机构");
        assertThat(beforeStart.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(beforeStart).get("code").asText())
                .isEqualTo("BUSINESS_RULE_VIOLATION");

        // 评估价值高于原值
        MvcResult tooHigh = register("ASSET-607", "VAL-607-HIGH", 0,
                "2025-02-01", "100000.01", "机构");
        assertThat(tooHigh.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(tooHigh).get("message").asText()).contains("原值");

        // 边界：价值为 0 允许，残值率 0
        MvcResult zero = register("ASSET-607", "VAL-607-ZERO", 0,
                "2025-02-01", "0", "机构");
        assertThat(zero.getResponse().getStatus()).isEqualTo(201);
        JsonNode zeroBody = json(zero);
        assertMoney(zeroBody, "residualValue", "0.00");
        assertMoney(zeroBody, "impairmentAmount", "100000.00");
        assertThat(zeroBody.get("residualRate").decimalValue())
                .isEqualByComparingTo("0.000000");

        // 边界：价值等于原值允许，减值为 0、残值率 1
        MvcResult full = register("ASSET-607", "VAL-607-FULL", 1,
                "2025-03-01", "100000.00", "机构");
        assertThat(full.getResponse().getStatus()).isEqualTo(201);
        JsonNode fullBody = json(full);
        assertMoney(fullBody, "impairmentAmount", "0.00");
        assertThat(fullBody.get("residualRate").decimalValue())
                .isEqualByComparingTo("1.000000");
    }

    @Test
    void rejectsInvalidRequestFieldsWith400() throws Exception {
        createLease("ASSET-608", "HT-608", "100000.00");

        MvcResult negative = register("ASSET-608", "VAL-608-NEG", 0,
                "2025-02-01", "-0.01", "机构");
        assertThat(negative.getResponse().getStatus()).isEqualTo(400);

        // 超过 2 位小数
        MvcResult tooPrecise = register("ASSET-608", "VAL-608-PRECISE", 0,
                "2025-02-01", "99.999", "机构");
        assertThat(tooPrecise.getResponse().getStatus()).isEqualTo(400);

        String missingFields = """
                {
                  "valuationNo": "",
                  "residualValue": 80000.00
                }
                """;
        MvcResult missing = mockMvc.perform(post("/api/assets/ASSET-608/valuations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingFields))
                .andExpect(status().isBadRequest())
                .andReturn();
        List<String> fieldNames = new ArrayList<>();
        json(missing).get("errors").forEach(e -> fieldNames.add(e.get("field").asText()));
        assertThat(fieldNames).contains("valuationNo", "expectedVersion",
                "valuationDate", "institution");

        assertThat(valuationCount("ASSET-608")).isZero();
    }

    @Test
    void halfUpRoundingForResidualRate() throws Exception {
        createLease("ASSET-609", "HT-609", "100000.00");

        // 33333.35 / 100000 = 0.3333335，保留 6 位 HALF_UP → 0.333334
        MvcResult result = register("ASSET-609", "VAL-609-1", 0,
                "2025-02-01", "33333.35", "机构");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);
        assertThat(body.get("residualRate").decimalValue())
                .isEqualByComparingTo("0.333334");
        assertMoney(body, "impairmentAmount", "66666.65");
    }

    @Test
    void returns404ForUnknownAssetAndEmptyHistory() throws Exception {
        createLease("ASSET-610", "HT-610", "100000.00");

        MvcResult unknownRegister = register("NO-SUCH-ASSET", "VAL-X", 0,
                "2025-02-01", "1.00", "机构");
        assertThat(unknownRegister.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(unknownRegister).get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");

        MvcResult unknownHistory = mockMvc.perform(
                get("/api/assets/NO-SUCH-ASSET/valuations")).andReturn();
        assertThat(unknownHistory.getResponse().getStatus()).isEqualTo(404);

        MvcResult unknownCurrent = mockMvc.perform(
                get("/api/assets/NO-SUCH-ASSET/valuations/current")).andReturn();
        assertThat(unknownCurrent.getResponse().getStatus()).isEqualTo(404);

        // 资产存在但尚无评估：历史为空数组、latestVersion 0；当前版本 404
        MvcResult emptyHistory = mockMvc.perform(
                get("/api/assets/ASSET-610/valuations")).andReturn();
        assertThat(emptyHistory.getResponse().getStatus()).isEqualTo(200);
        JsonNode history = json(emptyHistory);
        assertThat(history.get("latestVersion").asInt()).isZero();
        assertThat(history.get("valuations").size()).isZero();

        MvcResult noCurrent = mockMvc.perform(
                get("/api/assets/ASSET-610/valuations/current")).andReturn();
        assertThat(noCurrent.getResponse().getStatus()).isEqualTo(404);

        assertThat(valuationRepository.existsByValuationNo("VAL-X")).isFalse();
    }

    @Test
    void failedAttemptsDoNotLeaveGapsOrPartialRecords() throws Exception {
        createLease("ASSET-611", "HT-611", "100000.00");

        assertThat(register("ASSET-611", "VAL-611-1", 0, "2025-02-01", "90000.00", "机构")
                .getResponse().getStatus()).isEqualTo(201);

        // 一连串失败：过期版本、过早日期、超额价值、编号冲突
        register("ASSET-611", "VAL-611-A", 0, "2025-03-01", "80000.00", "机构");
        register("ASSET-611", "VAL-611-B", 1, "2025-01-01", "80000.00", "机构");
        register("ASSET-611", "VAL-611-C", 1, "2025-03-01", "100001.00", "机构");
        register("ASSET-611", "VAL-611-1", 1, "2025-03-01", "80000.00", "机构");

        var contract = contractRepository.findByContractNo("HT-611").orElseThrow();
        List<AssetValuation> persisted =
                valuationRepository.findByAssetIdOrderByVersionNoAscIdAsc(contract.getAsset().getId());
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getVersionNo()).isEqualTo(1);

        // 失败后追加成功，版本号严格连续
        MvcResult next = register("ASSET-611", "VAL-611-2", 1,
                "2025-03-01", "80000.00", "机构");
        assertThat(next.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(next).get("versionNo").asInt()).isEqualTo(2);

        persisted = valuationRepository
                .findByAssetIdOrderByVersionNoAscIdAsc(contract.getAsset().getId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(AssetValuation::getVersionNo)
                .containsExactly(1, 2);
    }
}
