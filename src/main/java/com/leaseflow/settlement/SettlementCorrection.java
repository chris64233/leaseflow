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
import java.time.LocalDate;

/**
 * 残值结算更正。
 *
 * <p>结算成功后需要修正时，只能通过本实体<strong>追加</strong>更正，不提供更新或删除入口：
 * 原始结算 {@link ResidualSettlement} 与其冻结的评估依据、合同规则、处置收入保持不变，
 * 每笔更正仅记录“更正后的有效金额”、相对上一有效结果的差异与更正原因，形成只增的更正链
 * （{@code seqNo} 从 1 起连续）。因此更正永远无法覆盖结算所采用的原始依据。
 */
@Entity
@Table(name = "residual_settlement_correction", uniqueConstraints = {
        @UniqueConstraint(name = "uk_residual_settlement_correction_no",
                columnNames = "correction_no"),
        @UniqueConstraint(name = "uk_residual_settlement_correction_seq",
                columnNames = {"settlement_id", "seq_no"})
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

    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    @Column(name = "correction_date", nullable = false)
    private LocalDate correctionDate;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    // ---- 更正后的有效输入（最新的修正口径；原始冻结依据仍在结算主表上）----

    @Column(name = "adjusted_residual_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustedResidualValue;

    @Column(name = "adjusted_disposal_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustedDisposalIncome;

    // ---- 更正后的有效结果 ----

    @Column(name = "adjusted_settlement_diff", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustedSettlementDiff;

    @Column(name = "adjusted_diff_direction", nullable = false, length = 16)
    private String adjustedDiffDirection;

    @Column(name = "adjusted_payable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustedPayableAmount;

    @Column(name = "adjusted_refundable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustedRefundableAmount;

    // ---- 相对上一有效结果的差异（更正影响额）----

    @Column(name = "diff_change", nullable = false, precision = 19, scale = 2)
    private BigDecimal diffChange;

    @Column(name = "payable_change", nullable = false, precision = 19, scale = 2)
    private BigDecimal payableChange;

    @Column(name = "residual_value_change", nullable = false, precision = 19, scale = 2)
    private BigDecimal residualValueChange;

    @Column(name = "disposal_income_change", nullable = false, precision = 19, scale = 2)
    private BigDecimal disposalIncomeChange;

    protected SettlementCorrection() {
    }

    public SettlementCorrection(String correctionNo, ResidualSettlement settlement, int seqNo,
                                LocalDate correctionDate, String reason,
                                BigDecimal adjustedResidualValue,
                                BigDecimal adjustedDisposalIncome,
                                BigDecimal adjustedSettlementDiff, String adjustedDiffDirection,
                                BigDecimal adjustedPayableAmount,
                                BigDecimal adjustedRefundableAmount,
                                BigDecimal diffChange, BigDecimal payableChange,
                                BigDecimal residualValueChange, BigDecimal disposalIncomeChange) {
        this.correctionNo = correctionNo;
        this.settlement = settlement;
        this.seqNo = seqNo;
        this.correctionDate = correctionDate;
        this.reason = reason;
        this.adjustedResidualValue = adjustedResidualValue;
        this.adjustedDisposalIncome = adjustedDisposalIncome;
        this.adjustedSettlementDiff = adjustedSettlementDiff;
        this.adjustedDiffDirection = adjustedDiffDirection;
        this.adjustedPayableAmount = adjustedPayableAmount;
        this.adjustedRefundableAmount = adjustedRefundableAmount;
        this.diffChange = diffChange;
        this.payableChange = payableChange;
        this.residualValueChange = residualValueChange;
        this.disposalIncomeChange = disposalIncomeChange;
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

    public String getReason() {
        return reason;
    }

    public BigDecimal getAdjustedResidualValue() {
        return adjustedResidualValue;
    }

    public BigDecimal getAdjustedDisposalIncome() {
        return adjustedDisposalIncome;
    }

    public BigDecimal getAdjustedSettlementDiff() {
        return adjustedSettlementDiff;
    }

    public String getAdjustedDiffDirection() {
        return adjustedDiffDirection;
    }

    public BigDecimal getAdjustedPayableAmount() {
        return adjustedPayableAmount;
    }

    public BigDecimal getAdjustedRefundableAmount() {
        return adjustedRefundableAmount;
    }

    public BigDecimal getDiffChange() {
        return diffChange;
    }

    public BigDecimal getPayableChange() {
        return payableChange;
    }

    public BigDecimal getResidualValueChange() {
        return residualValueChange;
    }

    public BigDecimal getDisposalIncomeChange() {
        return disposalIncomeChange;
    }
}
