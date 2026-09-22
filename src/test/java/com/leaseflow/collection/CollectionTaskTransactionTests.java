package com.leaseflow.collection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.payment.RentPaymentRepository;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 扫描结果与任务变更必须在同一事务内持久化：写入过程中抛错时整笔事务回滚，
 * 不留下任何催收任务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CollectionTaskTransactionTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CollectionTaskRepository taskRepository;

    @MockitoSpyBean
    private CollectionTaskRepository spiedTaskRepository;

    @Autowired
    private PaymentScheduleItemRepository scheduleItemRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private RentPaymentRepository paymentRepository;

    @BeforeEach
    void clearData() {
        spiedTaskRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        scheduleItemRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
    }

    private void createLease(String assetCode, String contractNo) {
        String body = """
                {
                  "assetCode": "%s",
                  "assetName": "数控机床",
                  "category": "生产设备",
                  "originalValue": 6000.00,
                  "contractNo": "%s",
                  "startDate": "2025-01-01",
                  "firstPaymentDate": "2025-02-01",
                  "financingAmount": 6000.00,
                  "nominalAnnualRate": 0,
                  "termMonths": 3
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

    @Test
    void rollsBackAllChangesWhenTaskPersistenceFails() throws Exception {
        createLease("A-520", "HT-520");
        assertThat(taskRepository.count()).isZero();

        // 模拟催收任务批量写入时数据库失败：3 个逾期期次一个都不得落库
        Mockito.doThrow(new RuntimeException("simulated persistence failure"))
                .when(spiedTaskRepository).saveAll(anyList());

        Exception failure = null;
        try {
            mockMvc.perform(post("/api/collection-tasks/scan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"businessDate": "2025-04-15"}
                                    """));
        } catch (Exception ex) {
            failure = ex;
        }
        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).contains("simulated persistence failure");

        Mockito.reset(spiedTaskRepository);

        // 事务整体回滚：不留下部分任务
        assertThat(taskRepository.count()).isZero();

        // 回滚后同一业务日期可重新扫描成功，生成完整的 3 条任务
        var retry = mockMvc.perform(post("/api/collection-tasks/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDate": "2025-04-15"}
                                """))
                .andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(taskRepository.count()).isEqualTo(3);
        assertThat(taskRepository.findAll())
                .allSatisfy(task -> {
                    assertThat(task.getStatus()).isEqualTo(CollectionTaskStatus.OPEN);
                    assertThat(task.getOverdueDays()).isPositive();
                });
    }
}
