package com.leaseflow.collection.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record OverdueScanRequest(
        @NotNull(message = "业务日期不能为空")
        LocalDate businessDate
) {
}
