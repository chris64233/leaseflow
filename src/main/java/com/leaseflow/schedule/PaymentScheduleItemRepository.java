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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from PaymentScheduleItem item "
            + "where item.dueDate < :businessDate order by item.id asc")
    List<PaymentScheduleItem> findOverdueItemsForUpdate(@Param("businessDate") LocalDate businessDate);
}
