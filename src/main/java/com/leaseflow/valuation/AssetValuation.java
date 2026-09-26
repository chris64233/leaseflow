package com.leaseflow.valuation;

import com.leaseflow.asset.LeasedAsset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 租赁物残值评估版本。
 *
 * <p>同一资产的评估记录构成不可变的版本链：{@code versionNo} 从 1 起连续递增，
 * 评估日期严格晚于链上已有记录。历史版本不允许覆盖或删除。
 * 减值金额与残值率在落库前按固定精度（金额 2 位、残值率 6 位，HALF_UP）计算并持久化。
 */
@Entity
@Table(name = "asset_valuation", uniqueConstraints = {
        @UniqueConstraint(name = "uk_asset_valuation_no", columnNames = "valuation_no"),
        @UniqueConstraint(name = "uk_asset_valuation_asset_version",
                columnNames = {"asset_id", "version_no"})
}, indexes = {
        @Index(name = "idx_asset_valuation_asset_version",
                columnList = "asset_id, version_no")
})
public class AssetValuation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "valuation_no", nullable = false, length = 64)
    private String valuationNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private LeasedAsset asset;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Column(name = "residual_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal residualValue;

    @Column(name = "impairment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal impairmentAmount;

    @Column(name = "residual_rate", nullable = false, precision = 11, scale = 6)
    private BigDecimal residualRate;

    @Column(name = "institution", nullable = false, length = 128)
    private String institution;

    protected AssetValuation() {
    }

    public AssetValuation(String valuationNo, LeasedAsset asset, int versionNo,
                          LocalDate valuationDate, BigDecimal residualValue,
                          BigDecimal impairmentAmount, BigDecimal residualRate,
                          String institution) {
        this.valuationNo = valuationNo;
        this.asset = asset;
        this.versionNo = versionNo;
        this.valuationDate = valuationDate;
        this.residualValue = residualValue;
        this.impairmentAmount = impairmentAmount;
        this.residualRate = residualRate;
        this.institution = institution;
    }

    public Long getId() {
        return id;
    }

    public String getValuationNo() {
        return valuationNo;
    }

    public LeasedAsset getAsset() {
        return asset;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public LocalDate getValuationDate() {
        return valuationDate;
    }

    public BigDecimal getResidualValue() {
        return residualValue;
    }

    public BigDecimal getImpairmentAmount() {
        return impairmentAmount;
    }

    public BigDecimal getResidualRate() {
        return residualRate;
    }

    public String getInstitution() {
        return institution;
    }
}
