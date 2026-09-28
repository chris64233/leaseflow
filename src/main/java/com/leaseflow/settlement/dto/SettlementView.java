package com.leaseflow.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 残值结算完整视图：含冻结的评估依据、合同计算规则、实际处置收入、原始最终金额与明细，
 * 以及截至目前的有效金额（经更正链修正后）与只追加的更正明细。
 *
 * <p>{@code replayed} 为 true 表示本次是相同结算编号、相同内容的幂等重放，返回首次结果。
 */
public record SettlementView(
        String settlementNo,
        String assetCode,
        String assetStatus,
        int expectedVersion,
        FrozenValuationBasis valuationBasis,
        FrozenContractBasis contractBasis,
        BigDecimal disposalIncome,
        LocalDate disposalDate,
        LocalDate settlementDate,
        BigDecimal settlementDiff,
        String diffDirection,
        BigDecimal payableAmount,
        BigDecimal refundableAmount,
        List<SettlementItemView> items,
        int appliedCorrectionCount,
        BigDecimal effectiveResidualValue,
        BigDecimal effectiveDisposalIncome,
        BigDecimal effectiveSettlementDiff,
        String effectiveDiffDirection,
        BigDecimal effectivePayableAmount,
        BigDecimal effectiveRefundableAmount,
        List<SettlementCorrectionView> corrections,
        boolean replayed
) {
}
