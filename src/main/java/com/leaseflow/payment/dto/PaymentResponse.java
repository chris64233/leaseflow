package com.leaseflow.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentResponse(PaymentView payment, PeriodView period) {

    public record PaymentView(String paymentNo, int periodNo, BigDecimal amount,
                              LocalDate paymentDate) {
    }

    public record PeriodView(int periodNo, BigDecimal dueAmount, BigDecimal paidAmount,
                             BigDecimal outstandingAmount, String paymentStatus) {
    }
}
