package com.leaseflow.settlement;

import com.leaseflow.asset.LeasedAsset;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 残值结算。
 *
 * <p>结算在确认时把三方计算依据一次性冻结为不可变快照，而不是只改动评估状态：
 * <ul>
 *   <li><b>评估依据</b>：结算所采用的最新评估版本（版本号、编号、日期、评估价值、
 *       减值金额、残值率），以标量列复制，事后评估链即使追加更正也不影响本结算；</li>
 *   <li><b>合同计算规则</b>：合同编号、起租日、原值、融资金额、名义年利率、还款方式
 *       及规则代码 {@code settlementRuleCode}；</li>
 *   <li><b>实际处置收入</b>与结算/处置日期。</li>
 * </ul>
 *
 * <p>最终金额（结算差额、应收/应付）与有序明细 {@link SettlementItem} 在同一事务内
 * 提交；结算成功后资产置为 {@code SETTLED}，不再接受普通评估。修正只能通过
 * {@link SettlementCorrection} 追加更正，原始结算记录与冻结依据永不覆盖。
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

    /** 客户端确认时看到的最新评估版本号；服务端在锁内比对，过期则 409。 */
    @Column(name = "expected_version", nullable = false)
    private int expectedVersion;

    // ---- 冻结：所采用的最新评估版本依据 ----

    @Column(name = "valuation_id", nullable = false)
    private long valuationId;

    @Column(name = "valuation_version_no", nullable = false)
    private int valuationVersionNo;

    @Column(name = "valuation_no", nullable = false, length = 64)
    private String valuationNo;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Column(name = "assessed_residual_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal assessedResidualValue;

    @Column(name = "assessed_impairment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal assessedImpairmentAmount;

    @Column(name = "assessed_residual_rate", nullable = false, precision = 11, scale = 6)
    private BigDecimal assessedResidualRate;

    @Column(name = "valuation_institution", nullable = false, length = 128)
    private String valuationInstitution;

    // ---- 冻结：租赁合同计算规则 ----

    @Column(name = "contract_no", nullable = false, length = 64)
    private String contractNo;

    @Column(name = "contract_start_date", nullable = false)
    private LocalDate contractStartDate;

    @Column(name = "original_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalValue;

    @Column(name = "financing_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal financingAmount;

    @Column(name = "nominal_annual_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal nominalAnnualRate;

    @Column(name = "repayment_method", nullable = false, length = 32)
    private String repaymentMethod;

    /** 结算采用的计算规则代码，随合同规则一并冻结。 */
    @Column(name = "settlement_rule_code", nullable = false, length = 64)
    private String settlementRuleCode;

    // ---- 实际处置与最终结果 ----

    @Column(name = "disposal_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal disposalIncome;

    @Column(name = "disposal_date", nullable = false)
    private LocalDate disposalDate;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /** 结算差额 = 评估残值 − 实际处置收入，2 位 HALF_UP；正为承租人应补，负为应退。 */
    @Column(name = "settlement_diff", nullable = false, precision = 19, scale = 2)
    private BigDecimal settlementDiff;

    /** 差额方向：PAYABLE（承租人应补）/REFUNDABLE（应退承租人）/EVEN（结清）。 */
    @Column(name = "diff_direction", nullable = false, length = 16)
    private String diffDirection;

    @Column(name = "payable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal payableAmount;

    @Column(name = "refundable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundableAmount;

    /** 截至目前已应用的更正笔数（不含原始结算）；原始结算恒为 0。 */
    @Column(name = "applied_correction_count", nullable = false)
    private int appliedCorrectionCount;

    /** 最近一次更正后的有效评估残值（无更正时等于冻结的评估残值）。 */
    @Column(name = "effective_residual_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectiveResidualValue;

    /** 最近一次更正后的有效处置收入（无更正时等于冻结的处置收入）。 */
    @Column(name = "effective_disposal_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectiveDisposalIncome;

    /** 最近一次更正后的有效结算差额。 */
    @Column(name = "effective_settlement_diff", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectiveSettlementDiff;

    @Column(name = "effective_diff_direction", nullable = false, length = 16)
    private String effectiveDiffDirection;

    @Column(name = "effective_payable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectivePayableAmount;

    @Column(name = "effective_refundable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal effectiveRefundableAmount;

    @OneToMany(mappedBy = "settlement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<SettlementItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "settlement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seqNo ASC")
    private List<SettlementCorrection> corrections = new ArrayList<>();

    protected ResidualSettlement() {
    }

    public ResidualSettlement(String settlementNo, LeasedAsset asset, int expectedVersion,
                              long valuationId, int valuationVersionNo, String valuationNo,
                              LocalDate valuationDate, BigDecimal assessedResidualValue,
                              BigDecimal assessedImpairmentAmount, BigDecimal assessedResidualRate,
                              String valuationInstitution, String contractNo,
                              LocalDate contractStartDate, BigDecimal originalValue,
                              BigDecimal financingAmount, BigDecimal nominalAnnualRate,
                              String repaymentMethod, String settlementRuleCode,
                              BigDecimal disposalIncome, LocalDate disposalDate,
                              LocalDate settlementDate, BigDecimal settlementDiff,
                              String diffDirection, BigDecimal payableAmount,
                              BigDecimal refundableAmount) {
        this.settlementNo = settlementNo;
        this.asset = asset;
        this.expectedVersion = expectedVersion;
        this.valuationId = valuationId;
        this.valuationVersionNo = valuationVersionNo;
        this.valuationNo = valuationNo;
        this.valuationDate = valuationDate;
        this.assessedResidualValue = assessedResidualValue;
        this.assessedImpairmentAmount = assessedImpairmentAmount;
        this.assessedResidualRate = assessedResidualRate;
        this.valuationInstitution = valuationInstitution;
        this.contractNo = contractNo;
        this.contractStartDate = contractStartDate;
        this.originalValue = originalValue;
        this.financingAmount = financingAmount;
        this.nominalAnnualRate = nominalAnnualRate;
        this.repaymentMethod = repaymentMethod;
        this.settlementRuleCode = settlementRuleCode;
        this.disposalIncome = disposalIncome;
        this.disposalDate = disposalDate;
        this.settlementDate = settlementDate;
        this.settlementDiff = settlementDiff;
        this.diffDirection = diffDirection;
        this.payableAmount = payableAmount;
        this.refundableAmount = refundableAmount;
        // 初始有效金额即原始冻结金额，尚无更正。
        this.appliedCorrectionCount = 0;
        this.effectiveResidualValue = assessedResidualValue;
        this.effectiveDisposalIncome = disposalIncome;
        this.effectiveSettlementDiff = settlementDiff;
        this.effectiveDiffDirection = diffDirection;
        this.effectivePayableAmount = payableAmount;
        this.effectiveRefundableAmount = refundableAmount;
    }

    public void addItem(SettlementItem item) {
        items.add(item);
    }

    public void addCorrection(SettlementCorrection correction) {
        corrections.add(correction);
        this.appliedCorrectionCount = corrections.size();
        this.effectiveResidualValue = correction.getAdjustedResidualValue();
        this.effectiveDisposalIncome = correction.getAdjustedDisposalIncome();
        this.effectiveSettlementDiff = correction.getAdjustedSettlementDiff();
        this.effectiveDiffDirection = correction.getAdjustedDiffDirection();
        this.effectivePayableAmount = correction.getAdjustedPayableAmount();
        this.effectiveRefundableAmount = correction.getAdjustedRefundableAmount();
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

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public long getValuationId() {
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

    public BigDecimal getAssessedResidualValue() {
        return assessedResidualValue;
    }

    public BigDecimal getAssessedImpairmentAmount() {
        return assessedImpairmentAmount;
    }

    public BigDecimal getAssessedResidualRate() {
        return assessedResidualRate;
    }

    public String getValuationInstitution() {
        return valuationInstitution;
    }

    public String getContractNo() {
        return contractNo;
    }

    public LocalDate getContractStartDate() {
        return contractStartDate;
    }

    public BigDecimal getOriginalValue() {
        return originalValue;
    }

    public BigDecimal getFinancingAmount() {
        return financingAmount;
    }

    public BigDecimal getNominalAnnualRate() {
        return nominalAnnualRate;
    }

    public String getRepaymentMethod() {
        return repaymentMethod;
    }

    public String getSettlementRuleCode() {
        return settlementRuleCode;
    }

    public BigDecimal getDisposalIncome() {
        return disposalIncome;
    }

    public LocalDate getDisposalDate() {
        return disposalDate;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public BigDecimal getSettlementDiff() {
        return settlementDiff;
    }

    public String getDiffDirection() {
        return diffDirection;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public BigDecimal getRefundableAmount() {
        return refundableAmount;
    }

    public int getAppliedCorrectionCount() {
        return appliedCorrectionCount;
    }

    public BigDecimal getEffectiveResidualValue() {
        return effectiveResidualValue;
    }

    public BigDecimal getEffectiveDisposalIncome() {
        return effectiveDisposalIncome;
    }

    public BigDecimal getEffectiveSettlementDiff() {
        return effectiveSettlementDiff;
    }

    public String getEffectiveDiffDirection() {
        return effectiveDiffDirection;
    }

    public BigDecimal getEffectivePayableAmount() {
        return effectivePayableAmount;
    }

    public BigDecimal getEffectiveRefundableAmount() {
        return effectiveRefundableAmount;
    }

    public List<SettlementItem> getItems() {
        return items;
    }

    public List<SettlementCorrection> getCorrections() {
        return corrections;
    }
}
