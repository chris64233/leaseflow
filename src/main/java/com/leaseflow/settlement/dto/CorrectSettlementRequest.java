package com.leaseflow.settlement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 残值结算更正请求。
 *
 * <p>结算成功后修正只能走只追加的更正流程：本请求给出更正后的有效口径
 * （有效评估残值、有效实际处置收入），服务端据此重算有效结果并追加一笔更正，
 * 不覆盖原始结算冻结的评估依据与合同规则。
 */
public record CorrectSettlementRequest(
        @NotBlank(message = "更正编号不能为空")
        String correctionNo,

        @NotNull(message = "更正日期不能为空")
        LocalDate correctionDate,

        @NotBlank(message = "更正原因不能为空")
        String reason,

        @NotNull(message = "更正后有效评估残值不能为空")
        @DecimalMin(value = "0", inclusive = true, message = "有效评估残值不得小于 0")
        @Digits(integer = 17, fraction = 2, message = "有效评估残值最多保留 2 位小数")
        BigDecimal adjustedResidualValue,

        @NotNull(message = "更正后有效处置收入不能为空")
        @DecimalMin(value = "0", inclusive = true, message = "有效处置收入不得小于 0")
        @Digits(integer = 17, fraction = 2, message = "有效处置收入最多保留 2 位小数")
        BigDecimal adjustedDisposalIncome
) {
}
