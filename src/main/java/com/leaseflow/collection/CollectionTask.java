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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "collection_task", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collection_task_schedule_item",
                columnNames = "schedule_item_id")
}, indexes = {
        @Index(name = "idx_collection_task_status", columnList = "status")
})
public class CollectionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_item_id", nullable = false, unique = true)
    private PaymentScheduleItem scheduleItem;

    @Column(name = "overdue_days", nullable = false)
    private int overdueDays;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CollectionTaskStatus status = CollectionTaskStatus.OPEN;

    protected CollectionTask() {
    }

    public CollectionTask(PaymentScheduleItem scheduleItem, int overdueDays,
                          BigDecimal outstandingAmount) {
        this.scheduleItem = scheduleItem;
        this.overdueDays = overdueDays;
        this.outstandingAmount = outstandingAmount;
        this.status = CollectionTaskStatus.OPEN;
    }

    public void refresh(int overdueDays, BigDecimal outstandingAmount) {
        this.overdueDays = overdueDays;
        this.outstandingAmount = outstandingAmount;
    }

    public void close() {
        this.status = CollectionTaskStatus.CLOSED;
    }

    public Long getId() {
        return id;
    }

    public PaymentScheduleItem getScheduleItem() {
        return scheduleItem;
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
}
