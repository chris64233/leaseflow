package com.leaseflow.payment;

import com.leaseflow.schedule.PaymentScheduleItem;
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

@Entity
@Table(name = "rent_payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_rent_payment_no", columnNames = "payment_no")
}, indexes = {
        @Index(name = "idx_rent_payment_schedule_item", columnList = "schedule_item_id")
})
public class RentPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, length = 64)
    private String paymentNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_item_id", nullable = false)
    private PaymentScheduleItem scheduleItem;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    protected RentPayment() {
    }

    public RentPayment(String paymentNo, PaymentScheduleItem scheduleItem,
                       BigDecimal amount, LocalDate paymentDate) {
        this.paymentNo = paymentNo;
        this.scheduleItem = scheduleItem;
        this.amount = amount;
        this.paymentDate = paymentDate;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public PaymentScheduleItem getScheduleItem() {
        return scheduleItem;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }
}
