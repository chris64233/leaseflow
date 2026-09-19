package com.leaseflow.contract;

import com.leaseflow.asset.LeasedAsset;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "lease_contract", uniqueConstraints = {
        @UniqueConstraint(name = "uk_lease_contract_no", columnNames = "contract_no")
})
public class LeaseContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_no", nullable = false, length = 64)
    private String contractNo;

    @OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "asset_id", nullable = false, unique = true)
    private LeasedAsset asset;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "first_payment_date", nullable = false)
    private LocalDate firstPaymentDate;

    @Column(name = "financing_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal financingAmount;

    @Column(name = "nominal_annual_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal nominalAnnualRate;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    protected LeaseContract() {
    }

    public LeaseContract(String contractNo, LeasedAsset asset, LocalDate startDate,
                         LocalDate firstPaymentDate, BigDecimal financingAmount,
                         BigDecimal nominalAnnualRate, int termMonths) {
        this.contractNo = contractNo;
        this.asset = asset;
        this.startDate = startDate;
        this.firstPaymentDate = firstPaymentDate;
        this.financingAmount = financingAmount;
        this.nominalAnnualRate = nominalAnnualRate;
        this.termMonths = termMonths;
    }

    public Long getId() {
        return id;
    }

    public String getContractNo() {
        return contractNo;
    }

    public LeasedAsset getAsset() {
        return asset;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getFirstPaymentDate() {
        return firstPaymentDate;
    }

    public BigDecimal getFinancingAmount() {
        return financingAmount;
    }

    public BigDecimal getNominalAnnualRate() {
        return nominalAnnualRate;
    }

    public int getTermMonths() {
        return termMonths;
    }
}
