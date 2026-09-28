package com.leaseflow.settlement;

import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.contract.RepaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 租赁结束后的残值结算单（每个租赁物至多一份，不可变）。
 *
 * <p>结算确认时将三类计算依据冻结在本行：
 * <ol>
 *   <li>最新评估版本（版本号、评估编号、评估日期、评估价值、减值金额、残值率）；</li>
 *   <li>实际处置情况（处置日期、处置收入、处置费用、净收入）；</li>
 *   <li>租赁合同计算规则（原值、融资金额、名义年利率、期数、还款方式）。</li>
 * </ol>
 *
 * <p>结算结果（差额、方向、应收/应付）与逐项明细（{@link SettlementItem}）
 * 同单持久化；结算后如需修正，只能通过 {@link SettlementCorrection} 追加更正，
 * 本行冻结的原始依据永不覆盖。
 */
@Entity
@Table(name = "residual_settlement", uniqueConstraints = {
        @UniqueConstraint(name = "uk_residual_settlement_no", columnNames = "settlement_no"),
        @UniqueConstraint(name = "uk_residual_settlement_asset", columnNames = "asset_id")
})
public class ResidualSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_no", nullable = false, length = 64)
    private String settlementNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private LeasedAsset asset;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    // ---- 冻结：所采用的最新评估版本快照 ----

    /** 结算采用的评估版本 id（评估版本链不可变，同时留存 id 以便追溯原记录）。 */
    @Column(name = "valuation_id", nullable = false)
    private Long valuationId;

    @Column(name = "valuation_version_no", nullable = false)
    private int valuationVersionNo;

    @Column(name = "valuation_no", nullable = false, length = 64)
    private String valuationNo;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Column(name = "frozen_residual_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal frozenResidualValue;

    @Column(name = "frozen_impairment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal frozenImpairmentAmount;

    @Column(name = "frozen_residual_rate", nullable = false, precision = 11, scale = 6)
    private BigDecimal frozenResidualRate;

    @Column(name = "frozen_institution", nullable = false, length = 128)
    private String frozenInstitution;

    // ---- 实际处置情况 ----

    @Column(name = "disposal_date", nullable = false)
    private LocalDate disposalDate;

    @Column(name = "disposal_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal disposalIncome;

    @Column(name = "disposal_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal disposalCost;

    @Column(name = "net_disposal_proceeds", nullable = false, precision = 19, scale = 2)
    private BigDecimal netDisposalProceeds;

    // ---- 冻结：租赁合同计算规则快照 ----

    @Column(name = "frozen_original_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal frozenOriginalValue;

    @Column(name = "frozen_financing_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal frozenFinancingAmount;

    @Column(name = "frozen_nominal_annual_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal frozenNominalAnnualRate;

    @Column(name = "frozen_term_months", nullable = false)
    private int frozenTermMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "frozen_repayment_method", nullable = false, length = 32)
    private RepaymentMethod frozenRepaymentMethod;

    // ---- 结算结果（原始结果；更正后的有效结果取最新更正） ----

    /** 差额 = 处置净收入 − 冻结评估残值；正数为盈余，负数为缺口。 */
    @Column(name = "difference_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal differenceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private SettlementDirection direction;

    /** 应收金额（盈余、需退回承租人一方）：差额为正时等于差额，否则为 0。 */
    @Column(name = "receivable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal receivableAmount;

    /** 应付金额（缺口、需补足）：差额为负时等于差额绝对值，否则为 0。 */
    @Column(name = "payable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal payableAmount;

    protected ResidualSettlement() {
    }

    public ResidualSettlement(String settlementNo, LeasedAsset asset, LocalDate settlementDate,
                              Long valuationId, int valuationVersionNo, String valuationNo,
                              LocalDate valuationDate, BigDecimal frozenResidualValue,
                              BigDecimal frozenImpairmentAmount, BigDecimal frozenResidualRate,
                              String frozenInstitution, LocalDate disposalDate,
                              BigDecimal disposalIncome, BigDecimal disposalCost,
                              BigDecimal netDisposalProceeds, BigDecimal frozenOriginalValue,
                              BigDecimal frozenFinancingAmount, BigDecimal frozenNominalAnnualRate,
                              int frozenTermMonths, RepaymentMethod frozenRepaymentMethod,
                              BigDecimal differenceAmount, SettlementDirection direction,
                              BigDecimal receivableAmount, BigDecimal payableAmount) {
        this.settlementNo = settlementNo;
        this.asset = asset;
        this.settlementDate = settlementDate;
        this.valuationId = valuationId;
        this.valuationVersionNo = valuationVersionNo;
        this.valuationNo = valuationNo;
        this.valuationDate = valuationDate;
        this.frozenResidualValue = frozenResidualValue;
        this.frozenImpairmentAmount = frozenImpairmentAmount;
        this.frozenResidualRate = frozenResidualRate;
        this.frozenInstitution = frozenInstitution;
        this.disposalDate = disposalDate;
        this.disposalIncome = disposalIncome;
        this.disposalCost = disposalCost;
        this.netDisposalProceeds = netDisposalProceeds;
        this.frozenOriginalValue = frozenOriginalValue;
        this.frozenFinancingAmount = frozenFinancingAmount;
        this.frozenNominalAnnualRate = frozenNominalAnnualRate;
        this.frozenTermMonths = frozenTermMonths;
        this.frozenRepaymentMethod = frozenRepaymentMethod;
        this.differenceAmount = differenceAmount;
        this.direction = direction;
        this.receivableAmount = receivableAmount;
        this.payableAmount = payableAmount;
    }

    public Long getId() {
        return id;
    }

    public String getSettlementNo() {
        return settlementNo;
    }

    public LeasedAsset getAsset() {
        return asset;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public Long getValuationId() {
        return valuationId;
    }

    public int getValuationVersionNo() {
        return valuationVersionNo;
    }

    public String getValuationNo() {
        return valuationNo;
    }

    public LocalDate getValuationDate() {
        return valuationDate;
    }

    public BigDecimal getFrozenResidualValue() {
        return frozenResidualValue;
    }

    public BigDecimal getFrozenImpairmentAmount() {
        return frozenImpairmentAmount;
    }

    public BigDecimal getFrozenResidualRate() {
        return frozenResidualRate;
    }

    public String getFrozenInstitution() {
        return frozenInstitution;
    }

    public LocalDate getDisposalDate() {
        return disposalDate;
    }

    public BigDecimal getDisposalIncome() {
        return disposalIncome;
    }

    public BigDecimal getDisposalCost() {
        return disposalCost;
    }

    public BigDecimal getNetDisposalProceeds() {
        return netDisposalProceeds;
    }

    public BigDecimal getFrozenOriginalValue() {
        return frozenOriginalValue;
    }

    public BigDecimal getFrozenFinancingAmount() {
        return frozenFinancingAmount;
    }

    public BigDecimal getFrozenNominalAnnualRate() {
        return frozenNominalAnnualRate;
    }

    public int getFrozenTermMonths() {
        return frozenTermMonths;
    }

    public RepaymentMethod getFrozenRepaymentMethod() {
        return frozenRepaymentMethod;
    }

    public BigDecimal getDifferenceAmount() {
        return differenceAmount;
    }

    public SettlementDirection getDirection() {
        return direction;
    }

    public BigDecimal getReceivableAmount() {
        return receivableAmount;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }
}
