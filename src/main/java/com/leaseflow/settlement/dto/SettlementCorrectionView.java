package com.leaseflow.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单笔结算更正视图（只追加，不覆盖原始冻结依据）。
 */
public record SettlementCorrectionView(
        String correctionNo,
        int seqNo,
        LocalDate correctionDate,
        String reason,
        BigDecimal adjustedResidualValue,
        BigDecimal adjustedDisposalIncome,
        BigDecimal adjustedSettlementDiff,
        String adjustedDiffDirection,
        BigDecimal adjustedPayableAmount,
        BigDecimal adjustedRefundableAmount,
        BigDecimal diffChange,
        BigDecimal payableChange,
        BigDecimal residualValueChange,
        BigDecimal disposalIncomeChange
) {
}
