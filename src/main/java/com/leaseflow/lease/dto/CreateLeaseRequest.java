package com.leaseflow.lease.dto;

import com.leaseflow.contract.RepaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateLeaseRequest(
        @NotBlank(message = "租赁物编码不能为空")
        String assetCode,

        @NotBlank(message = "租赁物名称不能为空")
        String assetName,

        @NotBlank(message = "租赁物分类不能为空")
        String category,

        @NotNull(message = "租赁物原值不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "租赁物原值必须大于 0")
        @Digits(integer = 17, fraction = 2, message = "租赁物原值最多保留 2 位小数")
        BigDecimal originalValue,

        @NotBlank(message = "合同编号不能为空")
        String contractNo,

        @NotNull(message = "起租日不能为空")
        LocalDate startDate,

        @NotNull(message = "首期应还日不能为空")
        LocalDate firstPaymentDate,

        @NotNull(message = "融资金额不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "融资金额必须大于 0")
        @Digits(integer = 17, fraction = 2, message = "融资金额最多保留 2 位小数")
        BigDecimal financingAmount,

        @NotNull(message = "名义年利率不能为空")
        @DecimalMin(value = "0", message = "名义年利率不得小于 0")
        @DecimalMax(value = "1", message = "名义年利率不得大于 1")
        BigDecimal nominalAnnualRate,

        @NotNull(message = "期数不能为空")
        @Min(value = 1, message = "期数不得小于 1")
        @Max(value = 120, message = "期数不得大于 120")
        Integer termMonths,

        RepaymentMethod repaymentMethod
) {
    /**
     * 返回实际采用的还款方式；未传时默认等额本金。
     */
    public RepaymentMethod effectiveRepaymentMethod() {
        return repaymentMethod == null ? RepaymentMethod.EQUAL_PRINCIPAL : repaymentMethod;
    }
}
