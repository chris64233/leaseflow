package com.leaseflow.schedule;

import com.leaseflow.contract.LeaseContract;
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
@Table(name = "payment_schedule_item")
public class PaymentScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private LeaseContract contract;

    @Column(name = "period_no", nullable = false)
    private int periodNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "opening_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingPrincipal;

    @Column(name = "principal_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalDue;

    @Column(name = "interest_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal interestDue;

    @Column(name = "total_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDue;

    @Column(name = "closing_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal closingPrincipal;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO.setScale(2);

    protected PaymentScheduleItem() {
    }

    public PaymentScheduleItem(LeaseContract contract, int periodNo, LocalDate dueDate,
                               BigDecimal openingPrincipal, BigDecimal principalDue,
                               BigDecimal interestDue, BigDecimal totalDue,
                               BigDecimal closingPrincipal) {
        this.contract = contract;
        this.periodNo = periodNo;
        this.dueDate = dueDate;
        this.openingPrincipal = openingPrincipal;
        this.principalDue = principalDue;
        this.interestDue = interestDue;
        this.totalDue = totalDue;
        this.closingPrincipal = closingPrincipal;
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

    public BigDecimal getPrincipalDue() {
        return principalDue;
    }

    public BigDecimal getInterestDue() {
        return interestDue;
    }

    public BigDecimal getTotalDue() {
        return totalDue;
    }

    public BigDecimal getClosingPrincipal() {
        return closingPrincipal;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void addPaidAmount(BigDecimal amount) {
        this.paidAmount = this.paidAmount.add(amount);
    }
}
