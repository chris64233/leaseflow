package com.leaseflow.settlement;

/**
 * 残值结算明细行类型。明细为固定计算链，按 {@link #lineNo} 升序展示。
 * 金额按 {@link #signed} 的约定带符号：
 * 加项为正、减项为负；资产原值与冻结评估残值作为基数行记为正，
 * 减值金额是原值到残值的减少项，记为负。
 */
public enum SettlementItemType {

    ORIGINAL_VALUE(10, "资产原值（冻结）", true),
    IMPAIRMENT(20, "累计减值（原值 − 评估残值）", false),
    RESIDUAL_VALUE(30, "结算采用评估残值（冻结）", true),
    DISPOSAL_INCOME(40, "实际处置收入", true),
    DISPOSAL_COST(50, "处置费用", false),
    NET_PROCEEDS(60, "处置净收入（处置收入 − 处置费用）", true),
    DIFFERENCE(70, "结算差额（处置净收入 − 评估残值）", true);

    private final int lineNo;
    private final String displayName;
    private final boolean signed;

    SettlementItemType(int lineNo, String displayName, boolean signed) {
        this.lineNo = lineNo;
        this.displayName = displayName;
        this.signed = signed;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 该明细行在加总链中的符号方向：{@code true} 加项（正），{@code false} 减项（负）。
     */
    public boolean isSigned() {
        return signed;
    }
}
