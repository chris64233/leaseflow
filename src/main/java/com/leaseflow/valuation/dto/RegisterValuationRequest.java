package com.leaseflow.valuation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RegisterValuationRequest(
        @NotBlank(message = "评估编号不能为空")
        String valuationNo,

        @NotNull(message = "客户端版本号不能为空")
        @PositiveOrZero(message = "客户端版本号必须大于等于 0")
        Integer expectedVersion,

        @NotNull(message = "评估日期不能为空")
        LocalDate valuationDate,

        @NotNull(message = "评估价值不能为空")
        @DecimalMin(value = "0", inclusive = true, message = "评估价值不得小于 0")
        @Digits(integer = 17, fraction = 2, message = "评估价值最多保留 2 位小数")
        BigDecimal residualValue,

        @NotBlank(message = "评估机构不能为空")
        String institution
) {
}
