package com.leaseflow.payment;

/**
 * 租金期次回款状态。
 *
 * <p>UNPAID：尚无回款；PARTIAL：累计已收小于应还总额；PAID：累计已收等于应还总额。
 */
public enum PaymentStatus {
    UNPAID,
    PARTIAL,
    PAID
}
