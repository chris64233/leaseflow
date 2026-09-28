package com.leaseflow.settlement;

import com.leaseflow.asset.AssetStatus;
import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.collection.CollectionTaskRepository;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.payment.RentPaymentRepository;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 残值结算接口测试：冻结计算依据、最终金额与明细、结算后拒绝普通评估、
 * 只追加更正不覆盖原始依据、编号幂等与查询完整依据。
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
    private PaymentScheduleItemRepository scheduleItemRepository;

    @Autowired
    private RentPaymentRepository paymentRepository;

    @Autowired
    private CollectionTaskRepository collectionTaskRepository;

    @Autowired
    private AssetValuationRepository valuationRepository;

    @Autowired
    private ResidualSettlementRepository settlementRepository;

    @Autowired
    private SettlementItemRepository itemRepository;

    @Autowired
    private SettlementCorrectionRepository correctionRepository;

    @BeforeEach
    void clearData() {
        correctionRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        valuationRepository.deleteAllInBatch();
        collectionTaskRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        scheduleItemRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        assetRepository.deleteAllInBatch();
    }

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
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .isEqualTo(201));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult registerValuation(String assetCode, String valuationNo, int expectedVersion,
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

    private MvcResult settle(String assetCode, String settlementNo, int expectedVersion,
                            String disposalIncome, String disposalDate) throws Exception {
        // 默认结算日期取处置日期，便于后续更正使用更晚日期。
        return settle(assetCode, settlementNo, expectedVersion, disposalIncome, disposalDate,
                disposalDate, "RESIDUAL_VS_DISPOSAL");
    }

    private MvcResult settle(String assetCode, String settlementNo, int expectedVersion,
                            String disposalIncome, String disposalDate, String settlementDate,
                            String ruleCode) throws Exception {
        String dateField = settlementDate == null ? ""
                : ",\"settlementDate\": \"%s\"".formatted(settlementDate);
        String body = """
                {
                  "settlementNo": "%s",
                  "expectedVersion": %d,
                  "disposalIncome": %s,
                  "disposalDate": "%s"%s,
                  "settlementRuleCode": "%s"
                }
                """.formatted(settlementNo, expectedVersion, disposalIncome, disposalDate,
                dateField, ruleCode);
        return mockMvc.perform(
                        post("/api/assets/{assetCode}/residual-settlements", assetCode)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn();
    }

    private MvcResult correct(String assetCode, String correctionNo, String correctionDate,
                              String reason, String adjustedResidual,
                              String adjustedIncome) throws Exception {
        String body = """
                {
                  "correctionNo": "%s",
                  "correctionDate": "%s",
                  "reason": "%s",
                  "adjustedResidualValue": %s,
                  "adjustedDisposalIncome": %s
                }
                """.formatted(correctionNo, correctionDate, reason, adjustedResidual,
                adjustedIncome);
        return mockMvc.perform(post(
                        "/api/assets/{assetCode}/residual-settlements/corrections", assetCode)
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

    private LeasedAsset asset(String assetCode) {
        return assetRepository.findByAssetCode(assetCode).orElseThrow();
    }

    @Test
    void confirmsSettlementFreezingBasisAmountAndItems() throws Exception {
        createLease("ASSET-801", "HT-801", "100000.00");
        assertThat(registerValuation("ASSET-801", "VAL-801-1", 0,
                "2025-03-01", "80000.00", "评估机构甲").getResponse().getStatus()).isEqualTo(201);

        MvcResult result = settle("ASSET-801", "STL-801-1", 1,
                "70000.00", "2025-04-01");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);

        assertThat(body.get("settlementNo").asText()).isEqualTo("STL-801-1");
        assertThat(body.get("assetCode").asText()).isEqualTo("ASSET-801");
        assertThat(body.get("assetStatus").asText()).isEqualTo("SETTLED");
        assertThat(body.get("expectedVersion").asInt()).isEqualTo(1);
        assertThat(body.get("replayed").asBoolean()).isFalse();

        // 冻结的评估依据：版本 1 的完整快照
        JsonNode vb = body.get("valuationBasis");
        assertThat(vb.get("versionNo").asInt()).isEqualTo(1);
        assertThat(vb.get("valuationNo").asText()).isEqualTo("VAL-801-1");
        assertThat(vb.get("valuationDate").asText()).isEqualTo("2025-03-01");
        assertMoney(vb, "assessedResidualValue", "80000.00");
        assertMoney(vb, "assessedImpairmentAmount", "20000.00");
        assertThat(vb.get("assessedResidualRate").decimalValue())
                .isEqualByComparingTo("0.800000");
        assertThat(vb.get("institution").asText()).isEqualTo("评估机构甲");

        // 冻结的合同计算规则
        JsonNode cb = body.get("contractBasis");
        assertThat(cb.get("contractNo").asText()).isEqualTo("HT-801");
        assertThat(cb.get("startDate").asText()).isEqualTo("2025-01-01");
        assertMoney(cb, "originalValue", "100000.00");
        assertMoney(cb, "financingAmount", "100000.00");
        assertThat(cb.get("nominalAnnualRate").decimalValue()).isEqualByComparingTo("0.120000");
        assertThat(cb.get("repaymentMethod").asText()).isEqualTo("EQUAL_PRINCIPAL");
        assertThat(cb.get("settlementRuleCode").asText()).isEqualTo("RESIDUAL_VS_DISPOSAL");

        // 实际处置与最终金额：80000 − 70000 = 10000，承租人应补
        assertMoney(body, "disposalIncome", "70000.00");
        assertMoney(body, "settlementDiff", "10000.00");
        assertThat(body.get("diffDirection").asText()).isEqualTo("PAYABLE");
        assertMoney(body, "payableAmount", "10000.00");
        assertMoney(body, "refundableAmount", "0.00");

        // 明细三行且带符号金额合计等于差额
        JsonNode items = body.get("items");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).get("itemCode").asText())
                .isEqualTo("RESIDUAL_VALUE");
        assertThat(items.get(0).get("direction").asText()).isEqualTo("ADD");
        assertMoney(items.get(0), "signedAmount", "80000.00");
        assertThat(items.get(1).get("itemCode").asText())
                .isEqualTo("DISPOSAL_INCOME");
        assertThat(items.get(1).get("direction").asText()).isEqualTo("DEDUCT");
        assertMoney(items.get(1), "signedAmount", "-70000.00");
        assertThat(items.get(2).get("itemCode").asText())
                .isEqualTo("SETTLEMENT_DIFF");
        assertThat(items.get(2).get("direction").asText()).isEqualTo("RESULT");
        assertMoney(items.get(2), "signedAmount", "10000.00");

        // 初始有效金额 = 原始金额，无更正
        assertThat(body.get("appliedCorrectionCount").asInt()).isZero();
        assertMoney(body, "effectiveResidualValue", "80000.00");
        assertMoney(body, "effectiveSettlementDiff", "10000.00");
        assertThat(body.get("effectiveDiffDirection").asText()).isEqualTo("PAYABLE");
        assertThat(body.get("corrections").size()).isZero();

        // 资产状态已落库为 SETTLED
        assertThat(asset("ASSET-801").getStatus()).isEqualTo(AssetStatus.SETTLED);
    }

    @Test
    void computesRefundableAndEvenDirections() throws Exception {
        createLease("ASSET-802", "HT-802", "100000.00");
        registerValuation("ASSET-802", "VAL-802-1", 0, "2025-03-01", "60000.00", "机构");

        // 处置收入高于评估残值：60000 − 75000 = -15000，应退
        MvcResult refund = settle("ASSET-802", "STL-802-1", 1,
                "75000.00", "2025-04-01");
        assertThat(refund.getResponse().getStatus()).isEqualTo(201);
        JsonNode rb = json(refund);
        assertMoney(rb, "settlementDiff", "-15000.00");
        assertThat(rb.get("diffDirection").asText()).isEqualTo("REFUNDABLE");
        assertMoney(rb, "payableAmount", "0.00");
        assertMoney(rb, "refundableAmount", "15000.00");

        // 另一资产：差额为 0，结清
        createLease("ASSET-803", "HT-803", "100000.00");
        registerValuation("ASSET-803", "VAL-803-1", 0, "2025-03-01", "60000.00", "机构");
        MvcResult even = settle("ASSET-803", "STL-803-1", 1,
                "60000.00", "2025-04-01");
        assertThat(even.getResponse().getStatus()).isEqualTo(201);
        JsonNode eb = json(even);
        assertMoney(eb, "settlementDiff", "0.00");
        assertThat(eb.get("diffDirection").asText()).isEqualTo("EVEN");
        assertMoney(eb, "payableAmount", "0.00");
        assertMoney(eb, "refundableAmount", "0.00");
    }

    @Test
    void freezesBasisAndDoesNotChangeWhenValuationChainGrowsBeforeSettlement()
            throws Exception {
        createLease("ASSET-804", "HT-804", "100000.00");
        registerValuation("ASSET-804", "VAL-804-1", 0, "2025-03-01", "80000.00", "机构");
        registerValuation("ASSET-804", "VAL-804-2", 1, "2025-05-01", "55000.00", "机构");

        // 基于最新版本 2 结算：冻结版本 2 而非版本 1
        MvcResult result = settle("ASSET-804", "STL-804-1", 2,
                "50000.00", "2025-06-01");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);
        assertThat(body.get("valuationBasis").get("versionNo").asInt()).isEqualTo(2);
        assertThat(body.get("valuationBasis").get("valuationNo").asText())
                .isEqualTo("VAL-804-2");
        assertMoney(body.get("valuationBasis"), "assessedResidualValue", "55000.00");
        assertMoney(body, "settlementDiff", "5000.00");
    }

    @Test
    void rejectsStaleVersionWith409() throws Exception {
        createLease("ASSET-805", "HT-805", "100000.00");
        registerValuation("ASSET-805", "VAL-805-1", 0, "2025-03-01", "80000.00", "机构");
        registerValuation("ASSET-805", "VAL-805-2", 1, "2025-05-01", "60000.00", "机构");

        // 基于过期版本 1 结算
        MvcResult stale = settle("ASSET-805", "STL-805-STALE", 1,
                "60000.00", "2025-06-01");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonNode err = json(stale);
        assertThat(err.get("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(err.get("message").asText()).contains("2");

        // 失败不留结算、不改变资产状态
        assertThat(settlementRepository.count()).isZero();
        assertThat(asset("ASSET-805").getStatus()).isEqualTo(AssetStatus.IN_LEASE);
        assertThat(itemRepository.count()).isZero();

        // 基于最新版本 2 仍可结算成功
        MvcResult ok = settle("ASSET-805", "STL-805-1", 2,
                "60000.00", "2025-06-01");
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        assertThat(asset("ASSET-805").getStatus()).isEqualTo(AssetStatus.SETTLED);
    }

    @Test
    void rejectsNormalValuationAfterSettlementButAllowsCorrection() throws Exception {
        createLease("ASSET-806", "HT-806", "100000.00");
        registerValuation("ASSET-806", "VAL-806-1", 0, "2025-03-01", "80000.00", "机构");
        settle("ASSET-806", "STL-806-1", 1, "70000.00", "2025-04-01");

        // 结算后普通评估被拒绝（400，业务规则），不新增版本
        MvcResult blocked = registerValuation("ASSET-806", "VAL-806-2", 1,
                "2025-05-01", "50000.00", "机构");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(blocked).get("code").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(valuationRepository.count()).isEqualTo(1);

        // 历史评估编号仍可幂等重放（200），返回冻结所依据的原版本
        MvcResult replay = registerValuation("ASSET-806", "VAL-806-1", 1,
                "2025-03-01", "80000.00", "机构");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(replay).get("versionNo").asInt()).isEqualTo(1);
        assertThat(valuationRepository.count()).isEqualTo(1);

        // 修正只能走更正流程
        MvcResult correction = correct("ASSET-806", "COR-806-1", "2025-05-10",
                "处置费用重估", "78000.00", "72000.00");
        assertThat(correction.getResponse().getStatus()).isEqualTo(201);
        JsonNode after = json(correction);
        assertThat(after.get("appliedCorrectionCount").asInt()).isEqualTo(1);
    }

    @Test
    void correctionAppendsWithoutOverwritingFrozenBasis() throws Exception {
        createLease("ASSET-807", "HT-807", "100000.00");
        registerValuation("ASSET-807", "VAL-807-1", 0, "2025-03-01", "80000.00", "机构");
        settle("ASSET-807", "STL-807-1", 1, "70000.00", "2025-04-01");

        // 更正：有效残值 78000、有效处置收入 72000 → 差额 6000
        MvcResult c1 = correct("ASSET-807", "COR-807-1", "2025-05-10",
                "评估口径修正", "78000.00", "72000.00");
        assertThat(c1.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(c1);

        // 原始冻结依据与原始结果不变
        assertThat(body.get("valuationBasis").get("versionNo").asInt()).isEqualTo(1);
        assertMoney(body.get("valuationBasis"), "assessedResidualValue", "80000.00");
        assertMoney(body, "disposalIncome", "70000.00");
        assertMoney(body, "settlementDiff", "10000.00");
        assertThat(body.get("diffDirection").asText()).isEqualTo("PAYABLE");

        // 有效结果更新为更正后口径
        assertMoney(body, "effectiveResidualValue", "78000.00");
        assertMoney(body, "effectiveDisposalIncome", "72000.00");
        assertMoney(body, "effectiveSettlementDiff", "6000.00");
        assertThat(body.get("effectiveDiffDirection").asText()).isEqualTo("PAYABLE");
        assertMoney(body, "effectivePayableAmount", "6000.00");

        // 更正明细含相对上一有效结果的影响额
        JsonNode corrections = body.get("corrections");
        assertThat(corrections.size()).isEqualTo(1);
        JsonNode corr = corrections.get(0);
        assertThat(corr.get("seqNo").asInt()).isEqualTo(1);
        assertMoney(corr, "adjustedSettlementDiff", "6000.00");
        assertMoney(corr, "diffChange", "-4000.00");
        assertMoney(corr, "payableChange", "-4000.00");
        assertMoney(corr, "residualValueChange", "-2000.00");
        assertMoney(corr, "disposalIncomeChange", "2000.00");

        // 第二笔更正：日期须严格晚于上一笔；方向翻转为应退
        MvcResult c2Wrong = correct("ASSET-807", "COR-807-X", "2025-05-10",
                "同日", "70000.00", "90000.00");
        assertThat(c2Wrong.getResponse().getStatus()).isEqualTo(409);

        MvcResult c2 = correct("ASSET-807", "COR-807-2", "2025-06-01",
                "处置价上调", "70000.00", "90000.00");
        assertThat(c2.getResponse().getStatus()).isEqualTo(201);
        JsonNode body2 = json(c2);
        assertThat(body2.get("appliedCorrectionCount").asInt()).isEqualTo(2);
        assertMoney(body2, "effectiveSettlementDiff", "-20000.00");
        assertThat(body2.get("effectiveDiffDirection").asText()).isEqualTo("REFUNDABLE");
        assertMoney(body2, "effectiveRefundableAmount", "20000.00");
        assertMoney(body2, "effectivePayableAmount", "0.00");
        assertThat(body2.get("corrections").size()).isEqualTo(2);
        assertThat(body2.get("corrections").get(1).get("seqNo").asInt()).isEqualTo(2);

        // 原始结算主记录仍只有 1 条，冻结明细仍为原始 3 行
        assertThat(settlementRepository.findByAssetId(asset("ASSET-807").getId()))
                .isPresent();
        assertThat(itemRepository.findBySettlementIdOrderByLineNoAsc(
                        settlementRepository.findByAssetId(asset("ASSET-807").getId())
                                .orElseThrow().getId()))
                .hasSize(3);
    }

    @Test
    void idempotentResubmitReturnsFirstSettlement() throws Exception {
        createLease("ASSET-808", "HT-808", "100000.00");
        registerValuation("ASSET-808", "VAL-808-1", 0, "2025-03-01", "80000.00", "机构");

        MvcResult first = settle("ASSET-808", "STL-808-DUP", 1,
                "70000.00", "2025-04-01");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(first).get("replayed").asBoolean()).isFalse();

        // 相同编号、相同内容重复提交：幂等返回首次结果（200），不新增结算/明细
        MvcResult retry = settle("ASSET-808", "STL-808-DUP", 1,
                "70000.00", "2025-04-01");
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        JsonNode rb = json(retry);
        assertThat(rb.get("replayed").asBoolean()).isTrue();
        assertThat(rb.get("settlementNo").asText()).isEqualTo("STL-808-DUP");
        assertMoney(rb, "settlementDiff", "10000.00");

        assertThat(settlementRepository.count()).isEqualTo(1);
        var stl = settlementRepository.findAll().get(0);
        assertThat(itemRepository.countBySettlementId(stl.getId())).isEqualTo(3);

        // 编号复用但内容不一致 → 409
        MvcResult conflict = settle("ASSET-808", "STL-808-DUP", 1,
                "65000.00", "2025-04-01");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(conflict).get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");
    }

    @Test
    void correctionIdempotentResubmitAndNoReuse() throws Exception {
        createLease("ASSET-809", "HT-809", "100000.00");
        registerValuation("ASSET-809", "VAL-809-1", 0, "2025-03-01", "80000.00", "机构");
        settle("ASSET-809", "STL-809-1", 1, "70000.00", "2025-04-01");

        MvcResult first = correct("ASSET-809", "COR-809-DUP", "2025-05-10",
                "原因", "76000.00", "70000.00");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        MvcResult replay = correct("ASSET-809", "COR-809-DUP", "2025-05-10",
                "原因", "76000.00", "70000.00");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(replay).get("replayed").asBoolean()).isTrue();
        assertThat(correctionRepository.count()).isEqualTo(1);

        MvcResult reused = correct("ASSET-809", "COR-809-DUP", "2025-06-01",
                "不同内容", "70000.00", "70000.00");
        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(reused).get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(correctionRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsSettleWithoutValuationAndBeforeDisposal() throws Exception {
        createLease("ASSET-810", "HT-810", "100000.00");

        // 无评估不能结算
        MvcResult noValuation = settle("ASSET-810", "STL-810-1", 0,
                "50000.00", "2025-04-01");
        assertThat(noValuation.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(noValuation).get("code").asText())
                .isEqualTo("BUSINESS_RULE_VIOLATION");

        registerValuation("ASSET-810", "VAL-810-1", 0, "2025-03-01", "80000.00", "机构");

        // 处置日期早于评估日期
        MvcResult earlyDisposal = settle("ASSET-810", "STL-810-2", 1,
                "50000.00", "2025-02-01");
        assertThat(earlyDisposal.getResponse().getStatus()).isEqualTo(400);

        // 结算日期早于处置日期
        MvcResult earlySettle = settle("ASSET-810", "STL-810-3", 1,
                "50000.00", "2025-04-01", "2025-03-01", "RESIDUAL_VS_DISPOSAL");
        assertThat(earlySettle.getResponse().getStatus()).isEqualTo(400);

        assertThat(settlementRepository.count()).isZero();
        assertThat(asset("ASSET-810").getStatus()).isEqualTo(AssetStatus.IN_LEASE);
    }

    @Test
    void cannotSettleTwiceAndCannotCorrectBeforeSettlement() throws Exception {
        createLease("ASSET-811", "HT-811", "100000.00");
        registerValuation("ASSET-811", "VAL-811-1", 0, "2025-03-01", "80000.00", "机构");
        assertThat(settle("ASSET-811", "STL-811-1", 1, "70000.00", "2025-04-01")
                .getResponse().getStatus()).isEqualTo(201);

        // 第二次确认（不同编号）被拒绝，引导走更正
        MvcResult again = settle("ASSET-811", "STL-811-2", 1,
                "60000.00", "2025-04-02");
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(again).get("message").asText()).contains("更正");
        assertThat(settlementRepository.count()).isEqualTo(1);

        // 未结算资产不能更正
        createLease("ASSET-812", "HT-812", "100000.00");
        registerValuation("ASSET-812", "VAL-812-1", 0, "2025-03-01", "80000.00", "机构");
        MvcResult noSettleCorrect = correct("ASSET-812", "COR-812-1", "2025-05-01",
                "原因", "70000.00", "70000.00");
        assertThat(noSettleCorrect.getResponse().getStatus()).isEqualTo(400);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void rejectsInvalidSettlementFieldsWith400() throws Exception {
        createLease("ASSET-813", "HT-813", "100000.00");
        registerValuation("ASSET-813", "VAL-813-1", 0, "2025-03-01", "80000.00", "机构");

        String missing = """
                {
                  "settlementNo": "",
                  "disposalIncome": 70000.00
                }
                """;
        MvcResult result = mockMvc.perform(
                        post("/api/assets/ASSET-813/residual-settlements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(missing))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        java.util.List<String> fields = new java.util.ArrayList<>();
        json(result).get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("settlementNo", "expectedVersion",
                "disposalDate", "settlementRuleCode");

        // 负处置收入、超精度
        assertThat(settle("ASSET-813", "STL-813-NEG", 1, "-0.01", "2025-04-01")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(settle("ASSET-813", "STL-813-P", 1, "70000.999", "2025-04-01")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void queryCompleteBasisByAssetAndByNoAnd404s() throws Exception {
        createLease("ASSET-814", "HT-814", "100000.00");
        registerValuation("ASSET-814", "VAL-814-1", 0, "2025-03-01", "80000.00", "机构");
        settle("ASSET-814", "STL-814-1", 1, "70000.00", "2025-04-01");

        MvcResult byAsset = mockMvc.perform(
                get("/api/assets/ASSET-814/residual-settlements/current")).andReturn();
        assertThat(byAsset.getResponse().getStatus()).isEqualTo(200);
        assertMoney(json(byAsset), "settlementDiff", "10000.00");

        MvcResult byNo = mockMvc.perform(
                get("/api/residual-settlements/STL-814-1")).andReturn();
        assertThat(byNo.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(byNo).get("assetCode").asText()).isEqualTo("ASSET-814");

        // 资产不存在、资产未结算、结算编号不存在 → 404
        assertThat(mockMvc.perform(get("/api/assets/NO-SUCH/residual-settlements/current"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/residual-settlements/NO-SUCH-STL"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        createLease("ASSET-815", "HT-815", "100000.00");
        registerValuation("ASSET-815", "VAL-815-1", 0, "2025-03-01", "80000.00", "机构");
        assertThat(mockMvc.perform(
                        get("/api/assets/ASSET-815/residual-settlements/current"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void settlementDateDefaultsToToday() throws Exception {
        createLease("ASSET-816", "HT-816", "100000.00");
        registerValuation("ASSET-816", "VAL-816-1", 0, "2025-03-01", "80000.00", "机构");
        // 不传 settlementDate：服务端取确认当日
        String body = """
                {
                  "settlementNo": "STL-816-1",
                  "expectedVersion": 1,
                  "disposalIncome": 70000.00,
                  "disposalDate": "2025-04-01",
                  "settlementRuleCode": "RESIDUAL_VS_DISPOSAL"
                }
                """;
        MvcResult result = mockMvc.perform(
                        post("/api/assets/ASSET-816/residual-settlements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(result).get("settlementDate").asText())
                .isEqualTo(LocalDate.now().toString());
    }
}
