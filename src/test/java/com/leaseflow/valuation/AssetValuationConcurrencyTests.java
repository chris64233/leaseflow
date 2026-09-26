package com.leaseflow.valuation;

import com.leaseflow.collection.CollectionTaskRepository;
import com.leaseflow.contract.LeaseContract;
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
 * 同一资产并发登记评估时，数据库约束与悲观锁必须保证只有一个基于当前版本的请求成功：
 * 一个 201，其余基于过期版本的请求 409；版本号连续无跳号。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssetValuationConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssetValuationRepository valuationRepository;

    @Autowired
    private PaymentScheduleItemRepository scheduleItemRepository;

    @Autowired
    private RentPaymentRepository paymentRepository;

    @Autowired
    private CollectionTaskRepository collectionTaskRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @BeforeEach
    void clearData() {
        valuationRepository.deleteAllInBatch();
        collectionTaskRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        scheduleItemRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
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

    private String requestBody(String valuationNo, int expectedVersion, String date,
                               String residualValue, String institution) {
        return """
                {
                  "valuationNo": "%s",
                  "expectedVersion": %d,
                  "valuationDate": "%s",
                  "residualValue": %s,
                  "institution": "%s"
                }
                """.formatted(valuationNo, expectedVersion, date, residualValue, institution);
    }

    private List<MvcResult> runConcurrent(String assetCode, int threads,
                                          RequestBuilder requestBuilder) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // 所有线程在发请求前对齐，尽可能同时进入事务。
        Phaser phaser = new Phaser(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<MvcResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                phaser.arriveAndAwaitAdvance();
                try {
                    return requestBuilder.submit(assetCode, index)
                            .andReturn();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
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

    @FunctionalInterface
    private interface RequestBuilder {
        org.springframework.test.web.servlet.ResultActions submit(String assetCode, int index)
                throws Exception;
    }

    private org.springframework.test.web.servlet.ResultActions valuationRequest(
            String assetCode, int index, String valuationNo, int expectedVersion, String date) {
        try {
            return mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(valuationNo, expectedVersion, date,
                            "80000.00", "评估机构")));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void onlyOneConcurrentRegistrationBasedOnCurrentVersionSucceeds() throws Exception {
        createLease("ASSET-701", "HT-701", "100000.00");

        int threads = 8;
        List<MvcResult> results = runConcurrent("ASSET-701", threads,
                (assetCode, index) -> valuationRequest(assetCode, index,
                        "VAL-701-%02d".formatted(index), 0,
                        "2025-03-%02d".formatted(1 + index)));

        int created = 0;
        int conflicted = 0;
        for (MvcResult result : results) {
            int status = result.getResponse().getStatus();
            assertThat(status).isIn(201, 409);
            if (status == 201) {
                created++;
            } else {
                conflicted++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(conflicted).isEqualTo(threads - 1);

        LeaseContract contract = contractRepository.findByContractNo("HT-701").orElseThrow();
        List<AssetValuation> persisted =
                valuationRepository.findByAssetIdOrderByVersionNoAscIdAsc(contract.getAsset().getId());
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getVersionNo()).isEqualTo(1);
    }

    @Test
    void nextVersionRaceAlsoAcceptsOnlyOneRequest() throws Exception {
        createLease("ASSET-702", "HT-702", "100000.00");

        // 先确定地建立版本 1。
        MvcResult first = valuationRequest("ASSET-702", 0, "VAL-702-1", 0, "2025-03-01")
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 多个请求都基于版本 1 并发追加版本 2：只允许一个成功，其余 409。
        int threads = 6;
        List<MvcResult> results = runConcurrent("ASSET-702", threads,
                (assetCode, index) -> valuationRequest(assetCode, index,
                        "VAL-702-N%02d".formatted(index), 1,
                        "2025-04-%02d".formatted(1 + index)));

        int created = 0;
        int conflicted = 0;
        for (MvcResult result : results) {
            int status = result.getResponse().getStatus();
            assertThat(status).isIn(201, 409);
            if (status == 201) {
                created++;
            } else {
                conflicted++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(conflicted).isEqualTo(threads - 1);

        LeaseContract contract = contractRepository.findByContractNo("HT-702").orElseThrow();
        List<AssetValuation> persisted =
                valuationRepository.findByAssetIdOrderByVersionNoAscIdAsc(contract.getAsset().getId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(AssetValuation::getVersionNo)
                .containsExactly(1, 2);
    }

    @Test
    void concurrentSameContentResubmissionsLeaveOnlyOneVersion() throws Exception {
        createLease("ASSET-703", "HT-703", "100000.00");

        int threads = 6;
        List<MvcResult> results = runConcurrent("ASSET-703", threads,
                (assetCode, index) -> valuationRequest(assetCode, index,
                        "VAL-703-SAME", 0, "2025-03-01"));

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

        LeaseContract contract = contractRepository.findByContractNo("HT-703").orElseThrow();
        List<AssetValuation> persisted =
                valuationRepository.findByAssetIdOrderByVersionNoAscIdAsc(contract.getAsset().getId());
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getVersionNo()).isEqualTo(1);
    }
}
