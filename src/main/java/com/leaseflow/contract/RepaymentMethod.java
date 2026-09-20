package com.leaseflow.contract;

/**
 * 租金还款方式。
 *
 * <p>EQUAL_PRINCIPAL：等额本金，每月应还本金固定，利息随剩余本金递减；
 * EQUAL_PAYMENT：等额本息，按标准年金公式计算固定月供，最后一期吸收累计舍入差额。
 */
public enum RepaymentMethod {
    EQUAL_PRINCIPAL,
    EQUAL_PAYMENT
}
