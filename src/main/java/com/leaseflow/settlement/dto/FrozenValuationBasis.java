package com.leaseflow.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 结算冻结的评估依据快照（来自结算确认时的最新评估版本，事后不再随评估链变化）。
 */
public record FrozenValuationBasis(
        long valuationId,
        int versionNo,
        String valuationNo,
        LocalDate valuationDate,
        BigDecimal assessedResidualValue,
        BigDecimal assessedImpairmentAmount,
        BigDecimal assessedResidualRate,
        String institution
) {
}
