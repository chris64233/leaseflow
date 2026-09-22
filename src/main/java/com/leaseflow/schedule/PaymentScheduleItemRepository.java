package com.leaseflow.schedule;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PaymentScheduleItemRepository extends JpaRepository<PaymentScheduleItem, Long> {

    List<PaymentScheduleItem> findByContractIdOrderByPeriodNoAsc(Long contractId);

    Optional<PaymentScheduleItem> findByContractIdAndPeriodNo(Long contractId, int periodNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from PaymentScheduleItem item "
            + "where item.contract.id = :contractId and item.periodNo = :periodNo")
    Optional<PaymentScheduleItem> findByContractIdAndPeriodNoForUpdate(
            @Param("contractId") Long contractId, @Param("periodNo") int periodNo);

    /**
     * 锁定到期日早于业务日期的全部期次（不含到期日当天），按到期日、合同、期次排序，
     * 供逾期扫描在同一事务内串行处理，防止并发扫描产生重复催收任务。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from PaymentScheduleItem item "
            + "where item.dueDate < :businessDate "
            + "order by item.dueDate asc, item.contract.contractNo asc, item.periodNo asc")
    List<PaymentScheduleItem> findOverdueCandidatesForUpdate(
            @Param("businessDate") LocalDate businessDate);
}
