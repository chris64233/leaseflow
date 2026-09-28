package com.leaseflow.settlement.dto;

import java.math.BigDecimal;

/**
 * 结算明细行视图。{@code signedAmount} 带符号：加项为正、减项为负。
 */
public record SettlementItemView(
        int lineNo,
        String itemType,
        String itemName,
        BigDecimal signedAmount
) {
}
