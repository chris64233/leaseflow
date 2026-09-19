package com.leaseflow.lease.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record LeaseDetailResponse(
        AssetView asset,
        ContractView contract,
        List<ScheduleItemView> schedule,
        SummaryView summary
) {
    public record AssetView(
            String assetCode,
            String assetName,
            String category,
            BigDecimal originalValue
    ) {
    }

    public record ContractView(
            String contractNo,
            LocalDate startDate,
            LocalDate firstDueDate,
            BigDecimal financingAmount,
            BigDecimal annualRate,
            int periods
    ) {
    }

    public record ScheduleItemView(
            int periodNo,
            LocalDate dueDate,
            BigDecimal openingPrincipal,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal total,
            BigDecimal closingPrincipal
    ) {
    }

    public record SummaryView(
            BigDecimal totalPrincipal,
            BigDecimal totalInterest,
            BigDecimal totalAmount
    ) {
    }
}
