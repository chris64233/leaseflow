package com.leaseflow.settlement.dto;

import com.leaseflow.settlement.SettlementDirection;

import java.math.BigDecimal;

/**
 * 一组结算金额结果：差额（带符号）、方向（SURPLUS 盈余 / DEFICIT 缺口）、
 * 应收金额（盈余应退回）与应付金额（缺口应补足）。
 */
public record SettlementAmountsView(
        BigDecimal differenceAmount,
        SettlementDirection direction,
        BigDecimal receivableAmount,
        BigDecimal payableAmount
) {
}
