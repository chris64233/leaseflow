package com.leaseflow.lease.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateLeaseRequest(
        @NotNull @Valid AssetPart asset,
        @NotNull @Valid ContractPart contract
) {
    public record AssetPart(
            @NotBlank(message = "租赁物编码不能为空")
            @Size(max = 64)
            String assetCode,

            @NotBlank(message = "租赁物名称不能为空")
            @Size(max = 128)
            String assetName,

            @NotBlank(message = "租赁物分类不能为空")
            @Size(max = 64)
            String category,

            @NotNull(message = "租赁物原值不能为空")
            @DecimalMin(value = "0", inclusive = false, message = "租赁物原值必须大于0")
            BigDecimal originalValue
    ) {
    }

    public record ContractPart(
            @NotBlank(message = "合同编号不能为空")
            @Size(max = 64)
            String contractNo,

            @NotNull(message = "起租日不能为空")
            LocalDate startDate,

            @NotNull(message = "首期应还日不能为空")
            LocalDate firstDueDate,

            @NotNull(message = "融资金额不能为空")
            @DecimalMin(value = "0", inclusive = false, message = "融资金额必须大于0")
            BigDecimal financingAmount,

            @NotNull(message = "名义年利率不能为空")
            @DecimalMin(value = "0", message = "名义年利率不得小于0")
            @DecimalMax(value = "1", message = "名义年利率不得大于1")
            BigDecimal annualRate,

            @NotNull(message = "期数不能为空")
            @Min(value = 1, message = "期数不得小于1")
            @Max(value = 120, message = "期数不得大于120")
            Integer periods
    ) {
    }
}
