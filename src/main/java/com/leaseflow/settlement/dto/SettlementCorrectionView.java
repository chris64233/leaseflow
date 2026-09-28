package com.leaseflow.settlement.dto;

import com.leaseflow.settlement.SettlementDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一条结算更正视图：调整额（带符号）以及应用该更正后的有效结算结果。
 */
public record SettlementCorrectionView(
        String correctionNo,
        int seqNo,
        LocalDate correctionDate,
        BigDecimal adjustmentAmount,
        String reason,
        BigDecimal effectiveDifference,
        SettlementDirection effectiveDirection,
        BigDecimal effectiveReceivable,
        BigDecimal effectivePayable
) {
}
