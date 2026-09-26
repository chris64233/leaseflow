package com.leaseflow.assessment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AssessmentView(
        String assessmentNo,
        String assetCode,
        int version,
        LocalDate assessmentDate,
        BigDecimal assessedValue,
        String appraiser,
        BigDecimal impairmentAmount,
        BigDecimal residualRate,
        boolean latest
) {
}
