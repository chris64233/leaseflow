package com.leaseflow.settlement.dto;

import java.math.BigDecimal;

/**
 * 结算明细行视图。{@code signedAmount} 为带符号金额（计入为正、冲减为负，结果行为差额本身），
 * 除结果行外各行带符号金额之和等于结算差额。
 */
public record SettlementItemView(
        int lineNo,
        String itemCode,
        String itemName,
        BigDecimal amount,
        String direction,
        BigDecimal signedAmount,
        String remark
) {
}
