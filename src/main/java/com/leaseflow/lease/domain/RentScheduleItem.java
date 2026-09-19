package com.leaseflow.lease.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "rent_schedule_item")
public class RentScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    private LeaseContract contract;

    @Column(name = "period_no", nullable = false)
    private int periodNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "opening_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingPrincipal;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "interest", nullable = false, precision = 19, scale = 2)
    private BigDecimal interest;

    @Column(name = "total", nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "closing_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal closingPrincipal;

    protected RentScheduleItem() {
    }

    public RentScheduleItem(int periodNo, LocalDate dueDate, BigDecimal openingPrincipal, BigDecimal principal,
                            BigDecimal interest, BigDecimal total, BigDecimal closingPrincipal) {
        this.periodNo = periodNo;
        this.dueDate = dueDate;
        this.openingPrincipal = openingPrincipal;
        this.principal = principal;
        this.interest = interest;
        this.total = total;
        this.closingPrincipal = closingPrincipal;
    }

    void assignContract(LeaseContract contract) {
        this.contract = contract;
    }

    public Long getId() {
        return id;
    }

    public LeaseContract getContract() {
        return contract;
    }

    public int getPeriodNo() {
        return periodNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getOpeningPrincipal() {
        return openingPrincipal;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public BigDecimal getInterest() {
        return interest;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getClosingPrincipal() {
        return closingPrincipal;
    }
}
