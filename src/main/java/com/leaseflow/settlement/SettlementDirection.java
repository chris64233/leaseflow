package com.leaseflow.settlement;

/**
 * 结算差额方向（含后续更正后的有效差额）。
 *
 * <p>SURPLUS：处置净收入 ≥ 冻结评估残值，承租人应退回盈余（应收）；
 * DEFICIT：处置净收入 < 冻结评估残值，承租人应补足缺口（应付）。
 * 差额恰为 0 时归为 SURPLUS，应收/应付金额均为 0。
 */
public enum SettlementDirection {
    SURPLUS,
    DEFICIT
}
