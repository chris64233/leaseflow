package com.leaseflow.contract;

/**
 * 租金还款方式。
 *
 * <p>EQUAL_PRINCIPAL：等额本金，每月应还本金固定；
 * EQUAL_PAYMENT：等额本息，每月应还总额按标准年金公式计算。
 */
public enum RepaymentMethod {
    EQUAL_PRINCIPAL,
    EQUAL_PAYMENT
}
