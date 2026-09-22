package com.leaseflow.collection.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CollectionTaskView(String contractNo, int periodNo, LocalDate dueDate,
                                 int overdueDays, BigDecimal outstandingAmount, String status) {
}
