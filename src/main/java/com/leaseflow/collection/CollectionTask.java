package com.leaseflow.collection;

import com.leaseflow.schedule.PaymentScheduleItem;
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

@Entity
@Table(name = "collection_task", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collection_task_schedule_item",
                columnNames = "schedule_item_id")
}, indexes = {
        @Index(name = "idx_collection_task_contract_no", columnList = "contract_no"),
        @Index(name = "idx_collection_task_status", columnList = "status")
})
public class CollectionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_item_id", nullable = false, unique = true)
    private PaymentScheduleItem scheduleItem;

    @Column(name = "contract_no", nullable = false, length = 64)
    private String contractNo;

    @Column(name = "period_no", nullable = false)
    private int periodNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "overdue_days", nullable = false)
    private int overdueDays;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CollectionTaskStatus status;

    protected CollectionTask() {
    }

    public CollectionTask(PaymentScheduleItem scheduleItem, String contractNo, int periodNo,
                          LocalDate dueDate, int overdueDays, BigDecimal outstandingAmount,
                          CollectionTaskStatus status) {
        this.scheduleItem = scheduleItem;
        this.contractNo = contractNo;
        this.periodNo = periodNo;
        this.dueDate = dueDate;
        this.overdueDays = overdueDays;
        this.outstandingAmount = outstandingAmount;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public PaymentScheduleItem getScheduleItem() {
        return scheduleItem;
    }

    public String getContractNo() {
        return contractNo;
    }

    public int getPeriodNo() {
        return periodNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public int getOverdueDays() {
        return overdueDays;
    }

    public BigDecimal getOutstandingAmount() {
        return outstandingAmount;
    }

    public CollectionTaskStatus getStatus() {
        return status;
    }

    public boolean refresh(int overdueDays, BigDecimal outstandingAmount) {
        boolean changed = this.overdueDays != overdueDays
                || this.outstandingAmount.compareTo(outstandingAmount) != 0;
        this.overdueDays = overdueDays;
        this.outstandingAmount = outstandingAmount;
        return changed;
    }

    public void close() {
        this.status = CollectionTaskStatus.CLOSED;
        this.outstandingAmount = BigDecimal.ZERO.setScale(2);
    }
}
