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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 资产状态、结算金额与明细必须在同一事务提交：持久化失败时整笔事务回滚，
 * 不留下结算、明细，也不把资产置为 SETTLED；回滚后可重新结算成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResidualSettlementTransactionTests {

    @Autowired
    private MockMvc mockMvc;

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

    @MockitoSpyBean
    private ResidualSettlementRepository spiedSettlementRepository;

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

    private void createLeaseWithValuation(String assetCode, String contractNo) {
        String leaseBody = """
                {
                  "assetCode": "%s",
                  "assetName": "数控机床",
                  "category": "生产设备",
                  "originalValue": 100000.00,
                  "contractNo": "%s",
                  "startDate": "2025-01-01",
                  "firstPaymentDate": "2025-02-01",
                  "financingAmount": 100000.00,
                  "nominalAnnualRate": 0.12,
                  "termMonths": 12
                }
                """.formatted(assetCode, contractNo);
        String valuationBody = """
                {
                  "valuationNo": "%s-V1",
                  "expectedVersion": 0,
                  "valuationDate": "2025-03-01",
                  "residualValue": 80000.00,
                  "institution": "评估机构"
                }
                """.formatted(assetCode);
        try {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(leaseBody))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
            mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(valuationBody))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String settlementBody(String settlementNo) {
        return """
                {
                  "settlementNo": "%s",
                  "expectedVersion": 1,
                  "disposalIncome": 70000.00,
                  "disposalDate": "2025-04-01",
                  "settlementDate": "2025-04-01",
                  "settlementRuleCode": "RESIDUAL_VS_DISPOSAL"
                }
                """.formatted(settlementNo);
    }

    @Test
    void rollsBackAssetStatusAmountAndItemsWhenPersistenceFails() throws Exception {
        createLeaseWithValuation("ASSET-920", "HT-920");
        assertThat(settlementRepository.count()).isZero();

        // 模拟结算主表写入失败：资产状态、金额、明细一个都不得落库
        Mockito.doThrow(new RuntimeException("simulated settlement persistence failure"))
                .when(spiedSettlementRepository)
                .saveAndFlush(Mockito.any(ResidualSettlement.class));

        Exception failure = null;
        try {
            mockMvc.perform(post("/api/assets/ASSET-920/residual-settlements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(settlementBody("STL-920-1")));
        } catch (Exception ex) {
            failure = ex;
        }
        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).contains("simulated settlement persistence failure");

        Mockito.reset(spiedSettlementRepository);

        // 整体回滚：无结算、无明细，资产仍是在租
        assertThat(settlementRepository.count()).isZero();
        assertThat(itemRepository.count()).isZero();
        LeasedAsset asset = assetRepository.findByAssetCode("ASSET-920").orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.IN_LEASE);

        // 回滚后可重新结算成功：状态、金额、明细一起提交
        var retry = mockMvc.perform(post("/api/assets/ASSET-920/residual-settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementBody("STL-920-1")))
                .andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(itemRepository.count()).isEqualTo(3);
        assertThat(assetRepository.findByAssetCode("ASSET-920").orElseThrow().getStatus())
                .isEqualTo(AssetStatus.SETTLED);
    }

    @Test
    void rollsBackCorrectionWhenPersistenceFails() throws Exception {
        createLeaseWithValuation("ASSET-921", "HT-921");
        var settled = mockMvc.perform(post("/api/assets/ASSET-921/residual-settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementBody("STL-921-1")))
                .andReturn();
        assertThat(settled.getResponse().getStatus()).isEqualTo(201);

        Mockito.doThrow(new RuntimeException("simulated correction persistence failure"))
                .when(spiedSettlementRepository)
                .saveAndFlush(Mockito.any(ResidualSettlement.class));

        String correctionBody = """
                {
                  "correctionNo": "COR-921-1",
                  "correctionDate": "2025-05-10",
                  "reason": "口径修正",
                  "adjustedResidualValue": 76000.00,
                  "adjustedDisposalIncome": 70000.00
                }
                """;
        Exception failure = null;
        try {
            mockMvc.perform(post("/api/assets/ASSET-921/residual-settlements/corrections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(correctionBody));
        } catch (Exception ex) {
            failure = ex;
        }
        assertThat(failure).isNotNull();

        Mockito.reset(spiedSettlementRepository);

        // 更正失败回滚：无更正记录，结算有效金额仍为原始结果
        assertThat(correctionRepository.count()).isZero();
        ResidualSettlement stl =
                settlementRepository.findByAssetId(assetRepository
                        .findByAssetCode("ASSET-921").orElseThrow().getId()).orElseThrow();
        assertThat(stl.getAppliedCorrectionCount()).isZero();
        assertThat(stl.getEffectiveSettlementDiff()).isEqualByComparingTo("10000.00");

        // 同一更正编号可重新提交成功
        var retry = mockMvc.perform(post("/api/assets/ASSET-921/residual-settlements/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody))
                .andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(correctionRepository.count()).isEqualTo(1);
    }
}
