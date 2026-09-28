package com.leaseflow.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * 残值结算明细行。一张结算单固定包含完整的计算链明细（见 {@link SettlementItemType}），
 * 与结算单、资产状态在同一事务内写入，失败时整体回滚，不留下半成品明细。
 */
@Entity
@Table(name = "settlement_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_settlement_item_line",
                columnNames = {"settlement_id", "line_no"})
}, indexes = {
        @Index(name = "idx_settlement_item_settlement", columnList = "settlement_id, line_no")
})
public class SettlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false)
    private ResidualSettlement settlement;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 32)
    private SettlementItemType itemType;

    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    /** 带符号金额：加项为正、减项为负（见 {@link SettlementItemType#isSigned()}）。 */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected SettlementItem() {
    }

    public SettlementItem(ResidualSettlement settlement, SettlementItemType itemType,
                          BigDecimal amount) {
        this.settlement = settlement;
        this.lineNo = itemType.getLineNo();
        this.itemType = itemType;
        this.itemName = itemType.getDisplayName();
        this.amount = amount;
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

    public SettlementItemType getItemType() {
        return itemType;
    }

    public String getItemName() {
        return itemName;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
