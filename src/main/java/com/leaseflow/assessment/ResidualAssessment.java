package com.leaseflow.assessment;

import com.leaseflow.asset.LeasedAsset;
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
 * 租赁物残值评估记录。每项租赁物对应一条不可变的版本链：
 * 版本号从 1 开始按资产严格递增，(asset_id, version) 与 assessment_no 均设有唯一约束，
 * 历史版本一旦写入不得更新或删除。
 */
@Entity
@Table(name = "residual_assessment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_residual_assessment_no", columnNames = "assessment_no"),
        @UniqueConstraint(name = "uk_residual_assessment_asset_version",
                columnNames = {"asset_id", "version"})
})
public class ResidualAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_no", nullable = false, length = 64)
    private String assessmentNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private LeasedAsset asset;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "assessment_date", nullable = false)
    private LocalDate assessmentDate;

    @Column(name = "assessed_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal assessedValue;

    @Column(name = "appraiser", nullable = false, length = 128)
    private String appraiser;

    @Column(name = "impairment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal impairmentAmount;

    @Column(name = "residual_rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal residualRate;

    protected ResidualAssessment() {
    }

    public ResidualAssessment(String assessmentNo, LeasedAsset asset, int version,
                              LocalDate assessmentDate, BigDecimal assessedValue,
                              String appraiser, BigDecimal impairmentAmount,
                              BigDecimal residualRate) {
        this.assessmentNo = assessmentNo;
        this.asset = asset;
        this.version = version;
        this.assessmentDate = assessmentDate;
        this.assessedValue = assessedValue;
        this.appraiser = appraiser;
        this.impairmentAmount = impairmentAmount;
        this.residualRate = residualRate;
    }

    public Long getId() {
        return id;
    }

    public String getAssessmentNo() {
        return assessmentNo;
    }

    public LeasedAsset getAsset() {
        return asset;
    }

    public int getVersion() {
        return version;
    }

    public LocalDate getAssessmentDate() {
        return assessmentDate;
    }

    public BigDecimal getAssessedValue() {
        return assessedValue;
    }

    public String getAppraiser() {
        return appraiser;
    }

    public BigDecimal getImpairmentAmount() {
        return impairmentAmount;
    }

    public BigDecimal getResidualRate() {
        return residualRate;
    }
}
