package com.leaseflow.collection.dto;

import java.time.LocalDate;

public record OverdueScanResponse(LocalDate businessDate, int createdCount,
                                  int updatedCount, int closedCount) {
}
