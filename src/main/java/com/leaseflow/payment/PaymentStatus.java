package com.leaseflow.payment;

import java.math.BigDecimal;

/**
 * 租金期次回款状态。
 *
 * <p>UNPAID：没有回款（已收金额为 0）；
 * PARTIAL：累计已收金额小于应还总额；
 * PAID：累计已收金额等于应还总额。
 */
public enum PaymentStatus {
    UNPAID,
    PARTIAL,
    PAID;

    public static PaymentStatus of(BigDecimal totalDue, BigDecimal paidAmount) {
        if (paidAmount.signum() == 0) {
            return UNPAID;
        }
        return paidAmount.compareTo(totalDue) >= 0 ? PAID : PARTIAL;
    }
}
