package com.leaseflow.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RegisterPaymentRequest(
        @NotBlank(message = "回款流水号不能为空")
        String paymentNo,

        @NotNull(message = "回款金额不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "回款金额必须大于 0")
        @Digits(integer = 17, fraction = 2, message = "回款金额最多保留 2 位小数")
        BigDecimal amount,

        @NotNull(message = "回款日期不能为空")
        LocalDate paymentDate
) {
}
