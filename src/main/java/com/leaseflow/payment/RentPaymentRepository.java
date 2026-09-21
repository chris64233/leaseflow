package com.leaseflow.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RentPaymentRepository extends JpaRepository<RentPayment, Long> {

    List<RentPayment> findByScheduleItemIdOrderByPaymentDateAscIdAsc(Long scheduleItemId);

    long countByScheduleItemId(Long scheduleItemId);
}
