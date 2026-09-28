package com.leaseflow.settlement;

import java.math.BigDecimal;

/**
 * 残值结算差额方向。
 */
public enum SettlementDirection {
    /** 承租人应补：评估残值高于实际处置收入，差额 &gt; 0。 */
    PAYABLE,
    /** 应退承租人：实际处置收入高于评估残值，差额 &lt; 0。 */
    REFUNDABLE,
    /** 结清：差额为 0。 */
    EVEN;

    /**
     * 按结算差额（评估残值 − 实际处置收入）推导方向。
     */
    public static SettlementDirection of(BigDecimal settlementDiff) {
        int cmp = settlementDiff.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            return PAYABLE;
        }
        if (cmp < 0) {
            return REFUNDABLE;
        }
        return EVEN;
    }
}
