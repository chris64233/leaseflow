package com.leaseflow.assessment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RegisterAssessmentRequest(
        @NotBlank(message = "评估编号不能为空")
        String assessmentNo,

        @NotNull(message = "评估日期不能为空")
        LocalDate assessmentDate,

        @NotNull(message = "评估价值不能为空")
        @DecimalMin(value = "0", message = "评估价值不得小于 0")
        @Digits(integer = 17, fraction = 2, message = "评估价值最多保留 2 位小数")
        BigDecimal assessedValue,

        @NotBlank(message = "评估机构不能为空")
        String appraiser,

        @NotNull(message = "基准版本不能为空")
        @Min(value = 0, message = "基准版本不得小于 0")
        Integer baseVersion
) {
}
