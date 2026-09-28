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
import java.time.LocalDate;

/**
 * 残值结算更正记录（只增不改）。
 *
 * <p>结算确认后不再接受普通评估；需要修正时只能追加更正记录。更正只表达
 * “在原结算差额基础上的调整额”，{@link ResidualSettlement} 中冻结的最新评估版本、
 * 实际处置收入与合同计算规则等原始依据永不覆盖：有效金额 = 原结算结果 + 历次更正调整额。
 * 更正编号全局唯一，相同编号与相同内容重复提交时幂等返回首次结果。
 */
@Entity
@Table(name = "settlement_correction", uniqueConstraints = {
        @UniqueConstraint(name = "uk_settlement_correction_no", columnNames = "correction_no")
}, indexes = {
        @Index(name = "idx_settlement_correction_settlement",
                columnList = "settlement_id, seq_no")
})
public class SettlementCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "correction_no", nullable = false, length = 64)
    private String correctionNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false)
    private ResidualSettlement settlement;

    /** 更正序号：同一结算单从 1 起连续递增，仅用于稳定排序。 */
    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    @Column(name = "correction_date", nullable = false)
    private LocalDate correctionDate;

    /**
     * 差额调整额，带符号：正数表示净收入调增/盈余增加（或缺口减少），
     * 负数表示净收入调减/缺口增加（或盈余减少）。不得为 0。
     */
    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustmentAmount;

    @Column(name = "reason", nullable = false, length = 256)
    private String reason;

    /** 应用本次更正后的有效差额（原结算差额 + 截至本次的全部调整额）。 */
    @Column(name = "effective_difference", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectiveDifference;

    @Enumerated(EnumType.STRING)
    @Column(name = "effective_direction", nullable = false, length = 16)
    private SettlementDirection effectiveDirection;

    @Column(name = "effective_receivable", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectiveReceivable;

    @Column(name = "effective_payable", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectivePayable;

    protected SettlementCorrection() {
    }

    public SettlementCorrection(String correctionNo, ResidualSettlement settlement, int seqNo,
                                LocalDate correctionDate, BigDecimal adjustmentAmount,
                                String reason, BigDecimal effectiveDifference,
                                SettlementDirection effectiveDirection,
                                BigDecimal effectiveReceivable, BigDecimal effectivePayable) {
        this.correctionNo = correctionNo;
        this.settlement = settlement;
        this.seqNo = seqNo;
        this.correctionDate = correctionDate;
        this.adjustmentAmount = adjustmentAmount;
        this.reason = reason;
        this.effectiveDifference = effectiveDifference;
        this.effectiveDirection = effectiveDirection;
        this.effectiveReceivable = effectiveReceivable;
        this.effectivePayable = effectivePayable;
    }

    public Long getId() {
        return id;
    }

    public String getCorrectionNo() {
        return correctionNo;
    }

    public ResidualSettlement getSettlement() {
        return settlement;
    }

    public int getSeqNo() {
        return seqNo;
    }

    public LocalDate getCorrectionDate() {
        return correctionDate;
    }

    public BigDecimal getAdjustmentAmount() {
        return adjustmentAmount;
    }

    public String getReason() {
        return reason;
    }

    public BigDecimal getEffectiveDifference() {
        return effectiveDifference;
    }

    public SettlementDirection getEffectiveDirection() {
        return effectiveDirection;
    }

    public BigDecimal getEffectiveReceivable() {
        return effectiveReceivable;
    }

    public BigDecimal getEffectivePayable() {
        return effectivePayable;
    }
}
