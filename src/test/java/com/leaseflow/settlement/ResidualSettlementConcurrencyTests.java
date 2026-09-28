package com.leaseflow.settlement;

import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.collection.CollectionTaskRepository;
import com.leaseflow.payment.RentPaymentRepository;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import com.leaseflow.valuation.AssetValuation;
import com.leaseflow.valuation.AssetValuationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

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
 * 新评估登记与残值结算确认并发时，双方都先对同一资产行加悲观写锁，
 * 并在锁内基于“进入锁时看到的最新评估版本”判定：
 *
 * <ul>
 *   <li>结算先拿到锁：结算成功并把资产置为 SETTLED，新评估随后被业务规则拒绝；</li>
 *   <li>新评估先拿到锁：产生新版本，结算基于过期版本得到 409。</li>
 * </ul>
 *
 * 无论调度顺序如何，结算成功与新版本产生互斥，恰好一方成功。
 * 另覆盖并发重复结算（编号不同 / 编号相同幂等）两种场景。
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
    private AssetValuationRepository valuationRepository;

    @Autowired
    private ResidualSettlementRepository settlementRepository;

    @Autowired
    private SettlementItemRepository itemRepository;

    @Autowired
    private SettlementCorrectionRepository correctionRepository;

    @Autowired
    private CollectionTaskRepository collectionTaskRepository;

    @Autowired
    private RentPaymentRepository paymentRepository;

    @Autowired
    private PaymentScheduleItemRepository scheduleItemRepository;

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

    /** 每轮对阵前重建“一份合同 + 版本 1 评估”的干净起点。 */
    private void resetFixture(String suffix) throws Exception {
        correctionRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        valuationRepository.deleteAllInBatch();
        collectionTaskRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        scheduleItemRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        assetRepository.deleteAllInBatch();
        createLease("ASSET-901", "HT-901");
        registerValuation("ASSET-901", "VAL-901-" + suffix, 0, "2025-03-01");
    }

    private void createLease(String assetCode, String contractNo) {
        String body = """
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

    private void registerValuation(String assetCode, String valuationNo, int expectedVersion,
                                   String date) throws Exception {
        String body = """
                {
                  "valuationNo": "%s",
                  "expectedVersion": %d,
                  "valuationDate": "%s",
                  "residualValue": 70000.00,
                  "institution": "中评评估机构"
                }
                """.formatted(valuationNo, expectedVersion, date);
        mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isEqualTo(201));
    }

    private ResultActions settlementRequest(String assetCode, String settlementNo) {
        String body = """
                {
                  "settlementNo": "%s",
                  "expectedVersion": 1,
                  "settlementDate": "2026-02-10",
                  "disposalDate": "2026-02-01",
                  "disposalIncome": 68000.00,
                  "disposalCost": 0.00
                }
                """.formatted(settlementNo);
        try {
            return mockMvc.perform(
                    post("/api/assets/{assetCode}/residual-settlement", assetCode)
                            .contentType(MediaType.APPLICATION_JSON).content(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ResultActions valuationRequest(String assetCode, String valuationNo, String date) {
        String body = """
                {
                  "valuationNo": "%s",
                  "expectedVersion": 1,
                  "valuationDate": "%s",
                  "residualValue": 65000.00,
                  "institution": "中评评估机构"
                }
                """.formatted(valuationNo, date);
        try {
            return mockMvc.perform(post("/api/assets/{assetCode}/valuations", assetCode)
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<MvcResult> runConcurrently(List<NamedRequest> requests) throws Exception {
        int threads = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Phaser phaser = new Phaser(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<MvcResult>> futures = new ArrayList<>();

        for (NamedRequest request : requests) {
            futures.add(pool.submit(() -> {
                phaser.arriveAndAwaitAdvance();
                try {
                    return request.action().get().andReturn();
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

    private record NamedRequest(String name, java.util.function.Supplier<ResultActions> action) {
    }

    @Test
    void concurrentSettlementAndNewValuationAreMutuallyExclusive() throws Exception {
        // 多轮重复以覆盖两种调度顺序（结算先得锁 / 新评估先得锁）。
        boolean sawSettlementWin = false;
        boolean sawValuationWin = false;
        for (int round = 0; round < 10; round++) {
            resetFixture("R%d".formatted(round));
            final int roundNo = round;

            List<MvcResult> results = runConcurrently(List.of(
                    new NamedRequest("settlement",
                            () -> settlementRequest("ASSET-901",
                                    "STL-901-R%d".formatted(roundNo))),
                    new NamedRequest("valuation",
                            () -> valuationRequest("ASSET-901",
                                    "VAL-901-N%d".formatted(roundNo), "2025-08-01"))
            ));

            int settlementStatus = results.get(0).getResponse().getStatus();
            int valuationStatus = results.get(1).getResponse().getStatus();

            LeasedAsset current = assetRepository.findByAssetCode("ASSET-901").orElseThrow();
            List<AssetValuation> versions = valuationRepository
                    .findByAssetIdOrderByVersionNoAscIdAsc(current.getId());
            boolean settled = settlementRepository.existsByAssetId(current.getId());

            if (settled) {
                // 结算先成功：结算 201；新评估必须被拒绝（结算后 400 业务规则；
                // 版本匹配判定先于状态判定的极端竞态下亦可能为 409，但绝不能产生新版本）。
                sawSettlementWin = true;
                assertThat(settlementStatus).isEqualTo(201);
                assertThat(valuationStatus).isIn(400, 409);
                assertThat(current.getStatus().name()).isEqualTo("SETTLED");
                assertThat(versions).hasSize(1);
            } else {
                // 新评估先成功：新版本产生，结算基于过期版本 409。
                sawValuationWin = true;
                assertThat(valuationStatus).isEqualTo(201);
                assertThat(settlementStatus).isEqualTo(409);
                assertThat(current.getStatus().name()).isEqualTo("IN_SERVICE");
                assertThat(versions).hasSize(2);
                assertThat(versions.get(1).getVersionNo()).isEqualTo(2);
            }
        }
        // 两种调度顺序都应在重复对阵中被观察到，否则互斥保证可能只是“一边倒”的假象。
        assertThat(sawSettlementWin).isTrue();
        assertThat(sawValuationWin).isTrue();
    }

    @Test
    void concurrentDuplicateSettlementsAcceptOnlyOne() throws Exception {
        createLease("ASSET-902", "HT-902");
        registerValuation("ASSET-902", "VAL-902-1", 0, "2025-03-01");

        int threads = 6;
        List<NamedRequest> requests = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            requests.add(new NamedRequest("settlement-" + i,
                    () -> settlementRequest("ASSET-902", "STL-902-%02d".formatted(index))));
        }
        List<MvcResult> results = runConcurrently(requests);

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

        LeaseContract contract = contractRepository.findByContractNo("HT-902").orElseThrow();
        ResidualSettlement settlement =
                settlementRepository.findByAssetId(contract.getAsset().getId()).orElseThrow();
        assertThat(itemRepository.findBySettlementIdOrderByLineNoAsc(settlement.getId()))
                .hasSize(7);
        assertThat(assetRepository.findById(contract.getAsset().getId()).orElseThrow()
                .getStatus().name()).isEqualTo("SETTLED");
    }

    @Test
    void concurrentSameSettlementNumberSubmissionsAreIdempotent() throws Exception {
        createLease("ASSET-903", "HT-903");
        registerValuation("ASSET-903", "VAL-903-1", 0, "2025-03-01");

        int threads = 6;
        List<NamedRequest> requests = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            requests.add(new NamedRequest("settlement",
                    () -> settlementRequest("ASSET-903", "STL-903-SAME")));
        }
        List<MvcResult> results = runConcurrently(requests);

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

        LeaseContract contract = contractRepository.findByContractNo("HT-903").orElseThrow();
        assertThat(settlementRepository.findByAssetId(contract.getAsset().getId())).isPresent();
    }
}
