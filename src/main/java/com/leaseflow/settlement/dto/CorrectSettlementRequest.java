package com.leaseflow.settlement.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 残值结算更正请求。更正以“原结算差额之上的调整额”追加，不覆盖结算冻结的原始依据。
 *
 * @param adjustmentAmount 差额调整额（带符号，不得为 0）：正数调增盈余/调减缺口，
 *                         负数调减盈余/调增缺口；非零校验在业务层完成
 */
public record CorrectSettlementRequest(
        @NotBlank(message = "更正编号不能为空")
        String correctionNo,

        @NotNull(message = "更正日期不能为空")
        LocalDate correctionDate,

        @NotNull(message = "调整金额不能为空")
        @Digits(integer = 17, fraction = 2, message = "调整金额最多保留 2 位小数")
        BigDecimal adjustmentAmount,

        @NotBlank(message = "更正原因不能为空")
        String reason
) {
}
