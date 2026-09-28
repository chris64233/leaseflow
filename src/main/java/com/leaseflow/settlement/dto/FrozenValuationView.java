package com.leaseflow.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 结算时冻结的最新评估版本快照。结算采用后该快照不再随后续任何操作改变。
 */
public record FrozenValuationView(
        String valuationNo,
        int versionNo,
        LocalDate valuationDate,
        BigDecimal residualValue,
        BigDecimal impairmentAmount,
        BigDecimal residualRate,
        String institution
) {
}
