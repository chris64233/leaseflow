package com.leaseflow.valuation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单个评估版本视图。{@code latest} 标明该版本是否为当前最新版本。
 */
public record ValuationView(
        String valuationNo,
        String assetCode,
        int versionNo,
        LocalDate valuationDate,
        BigDecimal residualValue,
        BigDecimal impairmentAmount,
        BigDecimal residualRate,
        String institution,
        boolean latest
) {
}
