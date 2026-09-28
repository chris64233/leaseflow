package com.leaseflow.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * 残值结算明细行，随结算一次性冻结，不可修改。
 *
 * <p>明细按 {@code lineNo} 有序展开“最终结算差额”的计算过程：
 * 计入项（{@link Direction#ADD}）与冲减项（{@link Direction#DEDUCT}）的
 * {@code signedAmount} 之和恰等于结果行（{@link Direction#RESULT}）的金额。
 */
@Entity
@Table(name = "residual_settlement_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_residual_settlement_item_line",
                columnNames = {"settlement_id", "line_no"})
})
public class SettlementItem {

    /** 明细行方向。 */
    public enum Direction {
        /** 计入结算（评估残值）。 */
        ADD,
        /** 冲减结算（实际处置收入）。 */
        DEDUCT,
        /** 结果行（结算差额），不参与加总。 */
        RESULT
    }

    /** 评估残值计入项。 */
    public static final String CODE_RESIDUAL_VALUE = "RESIDUAL_VALUE";
    /** 实际处置收入冲减项。 */
    public static final String CODE_DISPOSAL_INCOME = "DISPOSAL_INCOME";
    /** 结算差额结果项。 */
    public static final String CODE_SETTLEMENT_DIFF = "SETTLEMENT_DIFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false)
    private ResidualSettlement settlement;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "item_code", nullable = false, length = 48)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 128)
    private String itemName;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    @Column(name = "signed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal signedAmount;

    @Column(name = "remark", nullable = false, length = 255)
    private String remark;

    protected SettlementItem() {
    }

    public SettlementItem(ResidualSettlement settlement, int lineNo, String itemCode,
                          String itemName, BigDecimal amount, Direction direction,
                          BigDecimal signedAmount, String remark) {
        this.settlement = settlement;
        this.lineNo = lineNo;
        this.itemCode = itemCode;
        this.itemName = itemName;
        this.amount = amount;
        this.direction = direction.name();
        this.signedAmount = signedAmount;
        this.remark = remark;
    }

    public Long getId() {
        return id;
    }

    public ResidualSettlement getSettlement() {
        return settlement;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getItemCode() {
        return itemCode;
    }

    public String getItemName() {
        return itemName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Direction getDirection() {
        return Direction.valueOf(direction);
    }

    public BigDecimal getSignedAmount() {
        return signedAmount;
    }

    public String getRemark() {
        return remark;
    }
}
