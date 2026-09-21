package com.leaseflow.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentRegistrationResponse(PaymentView payment, PeriodView period) {

    public record PaymentView(String paymentNo, int periodNo, BigDecimal amount,
                              LocalDate paymentDate) {
    }

    public record PeriodView(int periodNo, BigDecimal totalDue, BigDecimal paidAmount,
                             BigDecimal outstandingAmount, String paymentStatus) {
    }
}
