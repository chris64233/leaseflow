package com.leaseflow.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentScheduleItemRepository extends JpaRepository<PaymentScheduleItem, Long> {

    List<PaymentScheduleItem> findByContractIdOrderByPeriodNoAsc(Long contractId);
}
