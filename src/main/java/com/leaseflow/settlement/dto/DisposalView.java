package com.leaseflow.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 实际处置情况（处置收入为登记值，处置费用可选，净收入 = 收入 − 费用）。
 */
public record DisposalView(
        LocalDate disposalDate,
        BigDecimal disposalIncome,
        BigDecimal disposalCost,
        BigDecimal netProceeds
) {
}
