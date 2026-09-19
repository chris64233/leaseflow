package com.leaseflow.lease.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "lease_contract", uniqueConstraints = @UniqueConstraint(columnNames = "contract_no"))
public class LeaseContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_no", nullable = false, length = 64)
    private String contractNo;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false, unique = true)
    private LeasedAsset asset;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "first_due_date", nullable = false)
    private LocalDate firstDueDate;

    @Column(name = "financing_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal financingAmount;

    @Column(name = "annual_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal annualRate;

    @Column(name = "periods", nullable = false)
    private int periods;

    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("periodNo ASC")
    private List<RentScheduleItem> scheduleItems = new ArrayList<>();

    protected LeaseContract() {
    }

    public LeaseContract(String contractNo, LeasedAsset asset, LocalDate startDate, LocalDate firstDueDate,
                         BigDecimal financingAmount, BigDecimal annualRate, int periods) {
        this.contractNo = contractNo;
        this.asset = asset;
        this.startDate = startDate;
        this.firstDueDate = firstDueDate;
        this.financingAmount = financingAmount;
        this.annualRate = annualRate;
        this.periods = periods;
    }

    public void addScheduleItem(RentScheduleItem item) {
        item.assignContract(this);
        this.scheduleItems.add(item);
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

    public LocalDate getFirstDueDate() {
        return firstDueDate;
    }

    public BigDecimal getFinancingAmount() {
        return financingAmount;
    }

    public BigDecimal getAnnualRate() {
        return annualRate;
    }

    public int getPeriods() {
        return periods;
    }

    public List<RentScheduleItem> getScheduleItems() {
        return scheduleItems;
    }
}
