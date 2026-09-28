package com.leaseflow.settlement;

import com.leaseflow.asset.AssetStatus;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 新评估与残值结算并发时，资产行悲观写锁 + 评估版本比对必须保证只有一方成功：
 * 要么唯一的新评估成功（基于旧版本的结算全部 409），要么唯一的结算成功
 * （结算后普通评估全部 400）；同时校验并发结算互斥与结算编号并发幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResidualSettlementConcurrencyTests {

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

    private void firstValuation(String assetCode, String valuationNo, String date,
                                String value) {
        String body = """
                {
                  "valuationNo": "%s",
                  "expectedVersion": 0,
                  "valuationDate": "%s",
                  "residualValue": %s,
                  "institution": "评估机构"
                }
                """.formatted(valuationNo, date, value);
        try {
            mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .isEqualTo(201));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult valuationRequest(String assetCode, String valuationNo,
                                       int expectedVersion, String date) {
        String body = """
                {
                  "valuationNo": "%s",
                  "expectedVersion": %d,
                  "valuationDate": "%s",
                  "residualValue": 60000.00,
                  "institution": "评估机构"
                }
                """.formatted(valuationNo, expectedVersion, date);
        try {
            return mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult settlementRequest(String assetCode, String settlementNo,
                                        int expectedVersion, String disposalDate) {
        String body = """
                {
                  "settlementNo": "%s",
                  "expectedVersion": %d,
                  "disposalIncome": 50000.00,
                  "disposalDate": "%s",
                  "settlementRuleCode": "RESIDUAL_VS_DISPOSAL"
                }
                """.formatted(settlementNo, expectedVersion, disposalDate);
        try {
            return mockMvc.perform(
                            post("/api/assets/{assetCode}/residual-settlements", assetCode)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andReturn();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<MvcResult> runConcurrent(int threads, java.util.function.IntFunction<MvcResult> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Phaser phaser = new Phaser(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                phaser.arriveAndAwaitAdvance();
                try {
                    return task.apply(index);
                } finally {
                    done.countDown();
                }
            }));
        }
        done.await();
        pool.shutdown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    @Test
    void newValuationAndSettlementRaceOnlyOneSideSucceeds() throws Exception {
        createLease("ASSET-901", "HT-901", "100000.00");
        firstValuation("ASSET-901", "VAL-901-1", "2025-03-01", "80000.00");

        int valuationThreads = 6;
        int settlementThreads = 6;
        int total = valuationThreads + settlementThreads;

        List<MvcResult> results = runConcurrent(total, index -> {
            if (index < valuationThreads) {
                // 所有新评估都基于版本 1，评估日期各不相同且严格晚于版本 1
                return valuationRequest("ASSET-901", "VAL-901-N%02d".formatted(index),
                        1, "2025-05-%02d".formatted(2 + index));
            }
            int s = index - valuationThreads;
            // 所有结算都基于版本 1
            return settlementRequest("ASSET-901", "STL-901-N%02d".formatted(s),
                    1, "2025-09-%02d".formatted(1 + s));
        });

        int created = 0;
        int valuationCreated = 0;
        int settlementCreated = 0;
        for (int i = 0; i < results.size(); i++) {
            int status = results.get(i).getResponse().getStatus();
            assertThat(status).isIn(200, 201, 400, 409);
            if (status == 201) {
                created++;
                if (i < valuationThreads) {
                    valuationCreated++;
                } else {
                    settlementCreated++;
                }
            }
        }
        // 关键：新评估与结算合计只能有一个成功
        assertThat(created).isEqualTo(1);

        var asset = assetRepository.findByAssetCode("ASSET-901").orElseThrow();
        long valuationCount = valuationRepository
                .findByAssetIdOrderByVersionNoAscIdAsc(asset.getId()).size();
        long settlementCount = settlementRepository.count();

        if (settlementCreated == 1) {
            // 结算抢先：唯一成功的是结算，全部新评估被拒；资产 SETTLED，评估仍只有版本 1
            assertThat(valuationCreated).isZero();
            assertThat(valuationCount).isEqualTo(1);
            assertThat(settlementCount).isEqualTo(1);
            assertThat(asset.getStatus()).isEqualTo(AssetStatus.SETTLED);
            assertThat(itemRepository.count()).isEqualTo(3);
        } else {
            // 评估抢先：唯一成功的是新评估（推进到版本 2），基于版本 1 的结算全部失败
            assertThat(valuationCreated).isEqualTo(1);
            assertThat(valuationCount).isEqualTo(2);
            assertThat(settlementCount).isZero();
            assertThat(asset.getStatus()).isEqualTo(AssetStatus.IN_LEASE);
            assertThat(itemRepository.count()).isZero();
        }
    }

    @Test
    void concurrentSettlementsAcceptOnlyOne() throws Exception {
        createLease("ASSET-902", "HT-902", "100000.00");
        firstValuation("ASSET-902", "VAL-902-1", "2025-03-01", "80000.00");

        int threads = 6;
        List<MvcResult> results = runConcurrent(threads, index ->
                settlementRequest("ASSET-902", "STL-902-%02d".formatted(index),
                        1, "2025-09-%02d".formatted(1 + index)));

        int created = 0;
        for (MvcResult result : results) {
            int status = result.getResponse().getStatus();
            assertThat(status).isIn(201, 400, 409);
            if (status == 201) {
                created++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(itemRepository.count()).isEqualTo(3);
        var asset = assetRepository.findByAssetCode("ASSET-902").orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.SETTLED);
    }

    @Test
    void concurrentSameSettlementNoIsIdempotent() throws Exception {
        createLease("ASSET-903", "HT-903", "100000.00");
        firstValuation("ASSET-903", "VAL-903-1", "2025-03-01", "80000.00");

        int threads = 6;
        List<MvcResult> results = runConcurrent(threads, index ->
                settlementRequest("ASSET-903", "STL-903-SAME", 1, "2025-09-01"));

        int created = 0;
        int replayed = 0;
        for (MvcResult result : results) {
            int status = result.getResponse().getStatus();
            assertThat(status).isIn(200, 201);
            if (status == 201) {
                created++;
            } else {
                replayed++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1);
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(itemRepository.count()).isEqualTo(3);
    }
}
