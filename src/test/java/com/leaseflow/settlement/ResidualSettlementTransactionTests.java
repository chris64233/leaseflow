package com.leaseflow.settlement;

import com.leaseflow.asset.AssetStatus;
import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 资产状态、结算金额与明细必须在同一事务内提交：
 * 写入明细（或更正）过程中抛错时整笔事务回滚，
 * 不留下结算单、明细、更正，也不把资产留在 SETTLED。
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

    @MockitoSpyBean
    private SettlementItemRepository spiedItemRepository;

    @MockitoSpyBean
    private SettlementCorrectionRepository spiedCorrectionRepository;

    @BeforeEach
    void clearData() {
        Mockito.reset(spiedItemRepository, spiedCorrectionRepository);
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
                  "financingAmount": 80000.00,
                  "nominalAnnualRate": 0.12,
                  "termMonths": 12
                }
                """.formatted(assetCode, contractNo);
        String valuationBody = """
                {
                  "valuationNo": "VAL-%s",
                  "expectedVersion": 0,
                  "valuationDate": "2025-03-01",
                  "residualValue": 70000.00,
                  "institution": "中评评估机构"
                }
                """.formatted(contractNo);
        try {
            mockMvc.perform(post("/api/leases")
                            .contentType(MediaType.APPLICATION_JSON).content(leaseBody))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
            mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                            .contentType(MediaType.APPLICATION_JSON).content(valuationBody))
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
                  "settlementDate": "2026-02-10",
                  "disposalDate": "2026-02-01",
                  "disposalIncome": 68000.00,
                  "disposalCost": 0.00
                }
                """.formatted(settlementNo);
    }

    @Test
    void rollsBackSettlementWhenItemPersistenceFails() throws Exception {
        createLeaseWithValuation("ASSET-951", "HT-951");

        // 明细写入失败：结算单与资产状态更新必须一并回滚。
        Mockito.doThrow(new RuntimeException("simulated item persistence failure"))
                .when(spiedItemRepository).saveAll(Mockito.anyIterable());

        Exception failure = null;
        try {
            mockMvc.perform(post("/api/assets/{assetCode}/residual-settlement", "ASSET-951")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(settlementBody("STL-951-1")));
        } catch (Exception ex) {
            failure = ex;
        }
        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).contains("simulated item persistence failure");

        Mockito.reset(spiedItemRepository);

        LeaseContract contract = contractRepository.findByContractNo("HT-951").orElseThrow();
        Long assetId = contract.getAsset().getId();
        assertThat(settlementRepository.findByAssetId(assetId)).isNotPresent();
        assertThat(itemRepository.count()).isZero();
        assertThat(assetRepository.findById(assetId).orElseThrow().getStatus())
                .isEqualTo(AssetStatus.IN_SERVICE);

        // 回滚后可用相同编号重新结算成功：状态、金额、明细一次到位。
        var retry = mockMvc.perform(post("/api/assets/{assetCode}/residual-settlement",
                        "ASSET-951")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementBody("STL-951-1")))
                .andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);

        LeasedAsset asset = assetRepository.findById(assetId).orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.SETTLED);
        ResidualSettlement settlement =
                settlementRepository.findByAssetId(assetId).orElseThrow();
        assertThat(settlement.getSettlementNo()).isEqualTo("STL-951-1");
        assertThat(settlement.getDifferenceAmount()).isEqualByComparingTo("-2000.00");
        assertThat(itemRepository.findBySettlementIdOrderByLineNoAsc(settlement.getId()))
                .hasSize(7);
    }

    @Test
    void rollsBackCorrectionWhenCorrectionPersistenceFails() throws Exception {
        createLeaseWithValuation("ASSET-952", "HT-952");
        mockMvc.perform(post("/api/assets/{assetCode}/residual-settlement", "ASSET-952")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementBody("STL-952-1")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));

        Mockito.doThrow(new RuntimeException("simulated correction persistence failure"))
                .when(spiedCorrectionRepository).saveAndFlush(any());

        String correctionBody = """
                {
                  "correctionNo": "COR-952-1",
                  "correctionDate": "2026-03-01",
                  "adjustmentAmount": 3000.00,
                  "reason": "处置收入补登"
                }
                """;
        Exception failure = null;
        try {
            mockMvc.perform(post(
                            "/api/assets/{assetCode}/residual-settlement/corrections",
                            "ASSET-952")
                    .contentType(MediaType.APPLICATION_JSON).content(correctionBody));
        } catch (Exception ex) {
            failure = ex;
        }
        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).contains("simulated correction persistence failure");

        Mockito.reset(spiedCorrectionRepository);

        // 更正未留下任何记录；编号可重新使用。
        assertThat(correctionRepository.count()).isZero();
        mockMvc.perform(post(
                        "/api/assets/{assetCode}/residual-settlement/corrections",
                        "ASSET-952")
                        .contentType(MediaType.APPLICATION_JSON).content(correctionBody))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertThat(correctionRepository.count()).isEqualTo(1);
    }
}
