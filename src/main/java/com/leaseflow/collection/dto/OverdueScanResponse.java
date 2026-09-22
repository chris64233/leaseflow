package com.leaseflow.collection.dto;

import java.time.LocalDate;
import java.util.List;

public record OverdueScanResponse(LocalDate businessDate, long createdCount,
                                  long updatedCount, long closedCount,
                                  List<CollectionTaskView> tasks) {
}
