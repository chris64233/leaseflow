package com.leaseflow.settlement;

import com.leaseflow.asset.AssetStatus;
import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.valuation.AssetValuationRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 残值结算功能测试：金额与明细计算、计算依据冻结、幂等、结算后评估冻结、
 * 结算更正（只增不改原始依据）与错误分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResidualSettlementApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeasedAssetRepository assetRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private AssetValuationRepository valuationRepository;

    @Autowired
    private ResidualSettlementRepository settlementRepository;

    @Autowired
    private SettlementItemRepository itemRepository;

    @Autowired
    private SettlementCorrectionRepository correctionRepository;

    @Autowired
    private com.leaseflow.collection.CollectionTaskRepository collectionTaskRepository;

    @Autowired
    private com.leaseflow.payment.RentPaymentRepository paymentRepository;

    @Autowired
    private com.leaseflow.schedule.PaymentScheduleItemRepository scheduleItemRepository;

    @BeforeEach
    void clearData() {
        correctionRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        collectionTaskRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        valuationRepository.deleteAllInBatch();
        scheduleItemRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        assetRepository.deleteAllInBatch();
    }

    private void createLease(String assetCode, String contractNo, String originalValue,
                             String financingAmount, String repaymentMethod) {
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
                  "termMonths": 12,
                  "repaymentMethod": "%s"
                }
                """.formatted(assetCode, originalValue, contractNo, financingAmount,
                repaymentMethod);
        try {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void registerValuation(String assetCode, String valuationNo, int expectedVersion,
                                   String date, String residualValue) throws Exception {
        String body = """
                {
                  "valuationNo": "%s",
                  "expectedVersion": %d,
                  "valuationDate": "%s",
                  "residualValue": %s,
                  "institution": "中评评估机构"
                }
                """.formatted(valuationNo, expectedVersion, date, residualValue);
        mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private MvcResult confirmSettlement(String assetCode, String settlementNo,
                                        int expectedVersion, String settlementDate,
                                        String disposalDate, String income, String cost)
            throws Exception {
        String body = """
                {
                  "settlementNo": "%s",
                  "expectedVersion": %d,
                  "settlementDate": "%s",
                  "disposalDate": "%s",
                  "disposalIncome": %s,
                  "disposalCost": %s
                }
                """.formatted(settlementNo, expectedVersion, settlementDate, disposalDate,
                income, cost);
        return mockMvc.perform(post("/api/assets/{assetCode}/residual-settlement", assetCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode readTree(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static void assertMoney(JsonNode node, String pointer, String expected) {
        assertThat(node.at(pointer).decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal(expected));
    }

    @Test
    void settlementFreezesBasisAndProducesAmountsAndItems() throws Exception {
        createLease("ASSET-801", "HT-801", "150000.00", "120000.00", "EQUAL_PAYMENT");
        registerValuation("ASSET-801", "VAL-801-1", 0, "2025-03-01", "120000.00");
        registerValuation("ASSET-801", "VAL-801-2", 1, "2025-06-01", "90000.00");

        MvcResult result = confirmSettlement("ASSET-801", "STL-801-1", 2,
                "2026-02-10", "2026-02-01", "85000.00", "3000.00");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        JsonNode json = readTree(result);
        assertThat(json.get("settlementNo").asText()).isEqualTo("STL-801-1");
        assertThat(json.get("assetCode").asText()).isEqualTo("ASSET-801");
        assertThat(json.get("assetStatus").asText()).isEqualTo("SETTLED");

        // 冻结：最新评估版本（版本 2）。
        JsonNode valuation = json.get("basis").get("valuation");
        assertThat(valuation.get("valuationNo").asText()).isEqualTo("VAL-801-2");
        assertThat(valuation.get("versionNo").asInt()).isEqualTo(2);
        assertThat(valuation.get("valuationDate").asText()).isEqualTo("2025-06-01");
        assertMoney(valuation, "/residualValue", "90000.00");
        assertMoney(valuation, "/impairmentAmount", "60000.00");
        assertThat(valuation.get("residualRate").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("0.600000"));

        // 实际处置情况。
        JsonNode disposal = json.get("basis").get("disposal");
        assertMoney(disposal, "/disposalIncome", "85000.00");
        assertMoney(disposal, "/disposalCost", "3000.00");
        assertMoney(disposal, "/netProceeds", "82000.00");

        // 冻结：合同计算规则。
        JsonNode rule = json.get("basis").get("contractRule");
        assertMoney(rule, "/originalValue", "150000.00");
        assertMoney(rule, "/financingAmount", "120000.00");
        assertThat(rule.get("termMonths").asInt()).isEqualTo(12);
        assertThat(rule.get("repaymentMethod").asText()).isEqualTo("EQUAL_PAYMENT");

        // 结果：净收入 82000 − 残值 90000 = -8000 缺口。
        JsonNode resultNode = json.get("originalResult");
        assertMoney(resultNode, "/differenceAmount", "-8000.00");
        assertThat(resultNode.get("direction").asText()).isEqualTo("DEFICIT");
        assertMoney(resultNode, "/receivableAmount", "0.00");
        assertMoney(resultNode, "/payableAmount", "8000.00");
        assertMoney(json.get("effectiveResult"), "/payableAmount", "8000.00");

        // 明细链：原值、减值(-)、残值、收入、费用(-)、净收入、差额。
        JsonNode items = json.get("items");
        assertThat(items).hasSize(7);
        assertThat(items.get(0).get("itemType").asText()).isEqualTo("ORIGINAL_VALUE");
        assertThat(items.get(0).get("signedAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("150000.00"));
        assertThat(items.get(1).get("itemType").asText()).isEqualTo("IMPAIRMENT");
        assertThat(items.get(1).get("signedAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("-60000.00"));
        assertThat(items.get(2).get("signedAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("90000.00"));
        assertThat(items.get(3).get("signedAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("85000.00"));
        assertThat(items.get(4).get("signedAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("-3000.00"));
        assertThat(items.get(5).get("itemType").asText()).isEqualTo("NET_PROCEEDS");
        assertThat(items.get(5).get("signedAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("82000.00"));
        assertThat(items.get(6).get("itemType").asText()).isEqualTo("DIFFERENCE");
        assertThat(items.get(6).get("signedAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("-8000.00"));

        LeasedAsset asset = assetRepository.findByAssetCode("ASSET-801").orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.SETTLED);
        ResidualSettlement settlement =
                settlementRepository.findByAssetId(asset.getId()).orElseThrow();
        assertThat(itemRepository.findBySettlementIdOrderByLineNoAsc(settlement.getId()))
                .hasSize(7);
    }

    @Test
    void surplusSettlementDirectionAndZeroCost() throws Exception {
        createLease("ASSET-802", "HT-802", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-802", "VAL-802-1", 0, "2025-03-01", "70000.00");

        MvcResult result = confirmSettlement("ASSET-802", "STL-802-1", 1,
                "2026-02-10", "2026-02-01", "72500.00", "0.00");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        JsonNode json = readTree(result);
        assertMoney(json.get("basis").get("disposal"), "/netProceeds", "72500.00");
        JsonNode resultNode = json.get("originalResult");
        assertMoney(resultNode, "/differenceAmount", "2500.00");
        assertThat(resultNode.get("direction").asText()).isEqualTo("SURPLUS");
        assertMoney(resultNode, "/receivableAmount", "2500.00");
        assertMoney(resultNode, "/payableAmount", "0.00");
        assertThat(json.get("basis").get("contractRule").get("repaymentMethod").asText())
                .isEqualTo("EQUAL_PRINCIPAL");
    }

    @Test
    void settlementIsIdempotentAndQueryableWithFullBasis() throws Exception {
        createLease("ASSET-803", "HT-803", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-803", "VAL-803-1", 0, "2025-03-01", "70000.00");

        MvcResult first = confirmSettlement("ASSET-803", "STL-803-1", 1,
                "2026-02-10", "2026-02-01", "68000.00", "0.00");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 相同编号、相同内容重试：幂等返回首次结果 200。
        MvcResult retry = confirmSettlement("ASSET-803", "STL-803-1", 1,
                "2026-02-10", "2026-02-01", "68000.00", "0.00");
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(readTree(retry).get("originalResult").get("differenceAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("-2000.00"));

        LeaseContract contract = contractRepository.findByContractNo("HT-803").orElseThrow();
        assertThat(settlementRepository.findByAssetId(contract.getAsset().getId())).isPresent();
        assertThat(correctionRepository.count()).isZero();

        // GET 返回完整计算依据。
        MvcResult queried = mockMvc.perform(
                        get("/api/assets/{assetCode}/residual-settlement", "ASSET-803"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = readTree(queried);
        assertThat(json.get("settlementNo").asText()).isEqualTo("STL-803-1");
        assertThat(json.get("basis").get("valuation").get("valuationNo").asText())
                .isEqualTo("VAL-803-1");
        assertThat(json.get("items")).hasSize(7);
        assertThat(json.get("corrections")).isEmpty();
    }

    @Test
    void settlementNumberReuseWithDifferentContentConflicts() throws Exception {
        createLease("ASSET-804", "HT-804", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-804", "VAL-804-1", 0, "2025-03-01", "70000.00");
        assertThat(confirmSettlement("ASSET-804", "STL-DUP", 1,
                "2026-02-10", "2026-02-01", "68000.00", "0.00").getResponse().getStatus())
                .isEqualTo(201);

        // 同资产不同内容：409，且不能覆盖原始依据。
        MvcResult changed = confirmSettlement("ASSET-804", "STL-DUP", 1,
                "2026-02-10", "2026-02-01", "99000.00", "0.00");
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);

        // 换编号重复结算：同样 409。
        MvcResult again = confirmSettlement("ASSET-804", "STL-OTHER", 1,
                "2026-02-10", "2026-02-01", "68000.00", "0.00");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);

        // 原始依据保持不变。
        MvcResult queried = mockMvc.perform(
                        get("/api/assets/{assetCode}/residual-settlement", "ASSET-804"))
                .andExpect(status().isOk()).andReturn();
        assertMoney(readTree(queried).get("basis").get("disposal"), "/disposalIncome",
                "68000.00");
    }

    @Test
    void staleExpectedVersionConflicts() throws Exception {
        createLease("ASSET-805", "HT-805", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-805", "VAL-805-1", 0, "2025-03-01", "70000.00");
        registerValuation("ASSET-805", "VAL-805-2", 1, "2025-06-01", "60000.00");

        // 基于过期版本 1 结算：409，未产生结算。
        MvcResult result = confirmSettlement("ASSET-805", "STL-805-1", 1,
                "2026-02-10", "2026-02-01", "60000.00", "0.00");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("code").asText()).isEqualTo("VERSION_CONFLICT");

        LeasedAsset asset = assetRepository.findByAssetCode("ASSET-805").orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.IN_SERVICE);
        assertThat(settlementRepository.existsByAssetId(asset.getId())).isFalse();
    }

    @Test
    void valuationRejectedAfterSettlementButReplayStillAllowed() throws Exception {
        createLease("ASSET-806", "HT-806", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-806", "VAL-806-1", 0, "2025-03-01", "70000.00");
        assertThat(confirmSettlement("ASSET-806", "STL-806-1", 1,
                "2026-02-10", "2026-02-01", "68000.00", "0.00").getResponse().getStatus())
                .isEqualTo(201);

        // 新的普通评估被拒绝（400 业务规则），版本链保持冻结。
        String body = """
                {
                  "valuationNo": "VAL-806-2",
                  "expectedVersion": 1,
                  "valuationDate": "2026-03-01",
                  "residualValue": 65000.00,
                  "institution": "中评评估机构"
                }
                """;
        mockMvc.perform(post("/api/assets/{assetCode}/valuations", "ASSET-806")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .contains("BUSINESS_RULE_VIOLATION"));

        LeaseContract contract = contractRepository.findByContractNo("HT-806").orElseThrow();
        assertThat(valuationRepository
                .findByAssetIdOrderByVersionNoAscIdAsc(contract.getAsset().getId()))
                .hasSize(1);

        // 结算前评估的相同编号重试仍幂等返回 200，不受冻结影响。
        String replayBody = """
                {
                  "valuationNo": "VAL-806-1",
                  "expectedVersion": 1,
                  "valuationDate": "2025-03-01",
                  "residualValue": 70000.00,
                  "institution": "中评评估机构"
                }
                """;
        mockMvc.perform(post("/api/assets/{assetCode}/valuations", "ASSET-806")
                        .contentType(MediaType.APPLICATION_JSON).content(replayBody))
                .andExpect(status().isOk());
    }

    @Test
    void correctionsAppendAndRecomputeEffectiveAmountsWithoutTouchingBasis() throws Exception {
        createLease("ASSET-807", "HT-807", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-807", "VAL-807-1", 0, "2025-03-01", "70000.00");
        assertThat(confirmSettlement("ASSET-807", "STL-807-1", 1,
                "2026-02-10", "2026-02-01", "68000.00", "0.00").getResponse().getStatus())
                .isEqualTo(201);

        // 更正 1：净收入调增 3000，有效差额由 -2000 变为 +1000（缺口转盈余）。
        MvcResult c1 = postCorrection("ASSET-807", "COR-807-1", "2026-03-01",
                "3000.00", "处置收入补登");
        assertThat(c1.getResponse().getStatus()).isEqualTo(201);
        JsonNode c1json = readTree(c1);
        assertMoney(c1json.get("effectiveResult"), "/differenceAmount", "1000.00");
        assertThat(c1json.get("effectiveResult").get("direction").asText()).isEqualTo("SURPLUS");
        assertMoney(c1json.get("effectiveResult"), "/receivableAmount", "1000.00");
        assertMoney(c1json.get("effectiveResult"), "/payableAmount", "0.00");
        // 原始结果与冻结依据不变。
        assertMoney(c1json.get("originalResult"), "/differenceAmount", "-2000.00");
        assertMoney(c1json.get("basis").get("disposal"), "/disposalIncome", "68000.00");

        // 更正 2：调减 1500。
        MvcResult c2 = postCorrection("ASSET-807", "COR-807-2", "2026-04-01",
                "-1500.00", "补付处置费用");
        assertThat(c2.getResponse().getStatus()).isEqualTo(201);
        assertThat(readTree(c2).get("effectiveResult").get("differenceAmount").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("-500.00"));

        // 更正编号幂等。
        MvcResult replay = postCorrection("ASSET-807", "COR-807-2", "2026-04-01",
                "-1500.00", "补付处置费用");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);

        // 查询结果包含两条更正，序号连续，有效金额取最新更正。
        MvcResult queried = mockMvc.perform(
                        get("/api/assets/{assetCode}/residual-settlement", "ASSET-807"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = readTree(queried);
        JsonNode corrections = json.get("corrections");
        assertThat(corrections).hasSize(2);
        assertThat(corrections.get(0).get("seqNo").asInt()).isEqualTo(1);
        assertThat(corrections.get(1).get("seqNo").asInt()).isEqualTo(2);
        assertMoney(json.get("effectiveResult"), "/differenceAmount", "-500.00");
        assertThat(correctionRepository.count()).isEqualTo(2);
    }

    @Test
    void correctionValidationErrors() throws Exception {
        createLease("ASSET-808", "HT-808", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-808", "VAL-808-1", 0, "2025-03-01", "70000.00");
        assertThat(confirmSettlement("ASSET-808", "STL-808-1", 1,
                "2026-02-10", "2026-02-01", "68000.00", "0.00").getResponse().getStatus())
                .isEqualTo(201);

        // 调整金额为 0：400。
        assertThat(postCorrection("ASSET-808", "COR-808-Z", "2026-03-01",
                "0.00", "无意义更正").getResponse().getStatus()).isEqualTo(400);

        // 更正日期早于结算日期：400。
        assertThat(postCorrection("ASSET-808", "COR-808-D", "2026-01-01",
                "100.00", "日期错误").getResponse().getStatus()).isEqualTo(400);

        // 未结算资产更正：404。
        createLease("ASSET-809", "HT-809", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        registerValuation("ASSET-809", "VAL-809-1", 0, "2025-03-01", "70000.00");
        assertThat(postCorrection("ASSET-809", "COR-809-1", "2026-03-01",
                "100.00", "未结算先更正").getResponse().getStatus()).isEqualTo(404);

        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void settlementValidationErrors() throws Exception {
        createLease("ASSET-810", "HT-810", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        // 无评估直接结算：400。
        assertThat(confirmSettlement("ASSET-810", "STL-810-1", 0,
                "2026-02-10", "2026-02-01", "68000.00", "0.00").getResponse().getStatus())
                .isEqualTo(400);

        registerValuation("ASSET-810", "VAL-810-1", 0, "2025-03-01", "70000.00");
        // 处置日期早于评估日期：400。
        assertThat(confirmSettlement("ASSET-810", "STL-810-2", 1,
                "2026-02-10", "2025-02-01", "68000.00", "0.00").getResponse().getStatus())
                .isEqualTo(400);
        // 结算日期早于处置日期：400。
        assertThat(confirmSettlement("ASSET-810", "STL-810-3", 1,
                "2026-01-31", "2026-02-01", "68000.00", "0.00").getResponse().getStatus())
                .isEqualTo(400);
        // 处置费用超过收入导致负净收入：净收入允许为负（对应更大缺口），此处用收入为负校验 400。
        String body = """
                {
                  "settlementNo": "STL-810-4",
                  "expectedVersion": 1,
                  "settlementDate": "2026-02-10",
                  "disposalDate": "2026-02-01",
                  "disposalIncome": -1.00,
                  "disposalCost": "0.00"
                }
                """;
        mockMvc.perform(post("/api/assets/{assetCode}/residual-settlement", "ASSET-810")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        LeasedAsset asset = assetRepository.findByAssetCode("ASSET-810").orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.IN_SERVICE);
        assertThat(settlementRepository.existsByAssetId(asset.getId())).isFalse();
    }

    @Test
    void settlementOfUnknownAssetAndQueryBeforeSettlementAre404() throws Exception {
        assertThat(confirmSettlement("ASSET-NOPE", "STL-X", 0,
                "2026-02-10", "2026-02-01", "1.00", "0.00").getResponse().getStatus())
                .isEqualTo(404);

        createLease("ASSET-811", "HT-811", "100000.00", "80000.00", "EQUAL_PRINCIPAL");
        mockMvc.perform(get("/api/assets/{assetCode}/residual-settlement", "ASSET-811"))
                .andExpect(status().isNotFound());
    }

    private MvcResult postCorrection(String assetCode, String correctionNo, String date,
                                     String adjustment, String reason) throws Exception {
        String body = """
                {
                  "correctionNo": "%s",
                  "correctionDate": "%s",
                  "adjustmentAmount": %s,
                  "reason": "%s"
                }
                """.formatted(correctionNo, date, adjustment, reason);
        return mockMvc.perform(
                        post("/api/assets/{assetCode}/residual-settlement/corrections",
                                assetCode)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }
}
