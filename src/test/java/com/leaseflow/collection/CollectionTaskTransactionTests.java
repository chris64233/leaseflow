package com.leaseflow.collection;

import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.payment.RentPaymentRepository;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class CollectionTaskTransactionTests {

    @Autowired
    private CollectionTaskService collectionTaskService;

    @Autowired
    private CollectionTaskRepository taskRepository;

    @Autowired
    private TestLeaseFixture fixture;

    @Autowired
    private RentPaymentRepository paymentRepository;

    @Autowired
    private PaymentScheduleItemRepository scheduleItemRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private LeasedAssetRepository assetRepository;

    @MockitoSpyBean
    private CollectionTaskRepository spiedTaskRepository;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        paymentRepository.deleteAll();
        scheduleItemRepository.deleteAll();
        contractRepository.deleteAll();
        assetRepository.deleteAll();
        Mockito.reset(spiedTaskRepository);
    }

    @Test
    void scanRollsBackEverythingWhenPersistenceFails() {
        String contractNo = fixture.createLease("ASSET-601", "HT-601");
        assertThat(taskRepository.count()).isZero();

        doThrow(new DataIntegrityViolationException("forced flush failure"))
                .when(spiedTaskRepository).flush();

        assertThatThrownBy(() -> collectionTaskService.scanOverdue(LocalDate.of(2025, 4, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 事务整体回滚：不留下任何催收任务
        assertThat(taskRepository.count()).isZero();

        // 回滚后扫描服务仍可正常工作
        Mockito.reset(spiedTaskRepository);
        var result = collectionTaskService.scanOverdue(LocalDate.of(2025, 4, 2));
        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.updatedCount()).isZero();
        assertThat(result.closedCount()).isZero();
        assertThat(taskRepository.count()).isEqualTo(3);
        assertThat(contractNo).isEqualTo("HT-601");
    }
}
