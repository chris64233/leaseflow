package com.leaseflow.settlement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 残值结算确认请求。
 *
 * <p>结算把“最新评估版本 + 实际处置收入 + 合同计算规则”冻结为最终金额与明细：
 * {@code expectedVersion} 为客户端看到的最新评估版本号，服务端在锁内比对，
 * 与并发新评估互斥，只允许基于当前版本的一方成功。
 */
public record CreateSettlementRequest(
        @NotBlank(message = "结算编号不能为空")
        String settlementNo,

        @NotNull(message = "客户端评估版本号不能为空")
        @PositiveOrZero(message = "客户端评估版本号必须大于等于 0")
        Integer expectedVersion,

        @NotNull(message = "实际处置收入不能为空")
        @DecimalMin(value = "0", inclusive = true, message = "实际处置收入不得小于 0")
        @Digits(integer = 17, fraction = 2, message = "实际处置收入最多保留 2 位小数")
        BigDecimal disposalIncome,

        @NotNull(message = "处置日期不能为空")
        LocalDate disposalDate,

        LocalDate settlementDate,

        @NotBlank(message = "结算计算规则不能为空")
        String settlementRuleCode
) {
}
