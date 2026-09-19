package com.leaseflow.lease.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record LeaseResponse(AssetView asset, ContractView contract,
                            List<ScheduleItemView> schedule, SummaryView summary) {

    public record AssetView(String assetCode, String assetName, String category,
                            BigDecimal originalValue) {
    }

    public record ContractView(String contractNo, LocalDate startDate,
                               LocalDate firstPaymentDate, BigDecimal financingAmount,
                               BigDecimal nominalAnnualRate, int termMonths) {
    }

    public record ScheduleItemView(int periodNo, LocalDate dueDate,
                                   BigDecimal openingPrincipal, BigDecimal principalDue,
                                   BigDecimal interestDue, BigDecimal totalDue,
                                   BigDecimal closingPrincipal) {
    }

    public record SummaryView(BigDecimal totalPrincipal, BigDecimal totalInterest,
                              BigDecimal totalAmount) {
    }
}
