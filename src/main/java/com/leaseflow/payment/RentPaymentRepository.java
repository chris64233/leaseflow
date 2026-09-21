package com.leaseflow.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RentPaymentRepository extends JpaRepository<RentPayment, Long> {

    boolean existsByPaymentNo(String paymentNo);

    long countByScheduleItemId(Long scheduleItemId);
}
