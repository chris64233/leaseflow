package com.leaseflow.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 结算冻结的合同计算规则快照。
 */
public record FrozenContractBasis(
        String contractNo,
        LocalDate startDate,
        BigDecimal originalValue,
        BigDecimal financingAmount,
        BigDecimal nominalAnnualRate,
        String repaymentMethod,
        String settlementRuleCode
) {
}
