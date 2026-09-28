package com.leaseflow.settlement.dto;

import java.math.BigDecimal;

/**
 * 结算时冻结的租赁合同计算规则快照。
 */
public record FrozenContractRuleView(
        BigDecimal originalValue,
        BigDecimal financingAmount,
        BigDecimal nominalAnnualRate,
        int termMonths,
        String repaymentMethod
) {
}
