package com.leaseflow.collection;

import com.leaseflow.collection.dto.CollectionTaskView;
import com.leaseflow.collection.dto.OverdueScanResponse;
import com.leaseflow.common.exception.BusinessRuleViolationException;
import com.leaseflow.schedule.PaymentScheduleItem;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CollectionTaskService {

    private final PaymentScheduleItemRepository scheduleItemRepository;
    private final CollectionTaskRepository taskRepository;

    public CollectionTaskService(PaymentScheduleItemRepository scheduleItemRepository,
                                 CollectionTaskRepository taskRepository) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public OverdueScanResponse scanOverdue(LocalDate businessDate) {
        List<PaymentScheduleItem> overdueItems = scheduleItemRepository
                .findOverdueItemsForUpdate(businessDate);
        if (overdueItems.isEmpty()) {
            return new OverdueScanResponse(businessDate, 0, 0, 0);
        }

        Map<Long, CollectionTask> existingTasks = taskRepository
                .findByScheduleItemIdIn(overdueItems.stream().map(PaymentScheduleItem::getId).toList())
                .stream()
                .collect(Collectors.toMap(task -> task.getScheduleItem().getId(),
                        Function.identity()));

        int createdCount = 0;
        int updatedCount = 0;
        int closedCount = 0;

        for (PaymentScheduleItem item : overdueItems) {
            BigDecimal outstanding = item.getTotalDue().subtract(item.getPaidAmount());
            int overdueDays = (int) ChronoUnit.DAYS.between(item.getDueDate(), businessDate);
            CollectionTask existing = existingTasks.get(item.getId());

            if (outstanding.signum() <= 0) {
                if (existing != null && existing.getStatus() == CollectionTaskStatus.OPEN) {
                    existing.close();
                    closedCount++;
                }
                continue;
            }

            if (existing == null) {
                CollectionTask task = new CollectionTask(item, item.getContract().getContractNo(),
                        item.getPeriodNo(), item.getDueDate(), overdueDays, outstanding,
                        CollectionTaskStatus.OPEN);
                taskRepository.save(task);
                createdCount++;
            } else if (existing.getStatus() == CollectionTaskStatus.OPEN) {
                if (existing.refresh(overdueDays, outstanding)) {
                    updatedCount++;
                }
            }
        }

        taskRepository.flush();
        return new OverdueScanResponse(businessDate, createdCount, updatedCount, closedCount);
    }

    @Transactional(readOnly = true)
    public List<CollectionTaskView> queryTasks(String contractNo, String status) {
        String normalizedContractNo = contractNo == null || contractNo.isBlank()
                ? null : contractNo.trim();
        CollectionTaskStatus taskStatus = parseStatus(status);

        List<CollectionTask> tasks;
        if (normalizedContractNo != null && taskStatus != null) {
            tasks = taskRepository
                    .findByContractNoAndStatusOrderByDueDateAscPeriodNoAsc(
                            normalizedContractNo, taskStatus);
        } else if (normalizedContractNo != null) {
            tasks = taskRepository.findByContractNoOrderByDueDateAscPeriodNoAsc(
                    normalizedContractNo);
        } else if (taskStatus != null) {
            tasks = taskRepository.findByStatusOrderByDueDateAscContractNoAscPeriodNoAsc(
                    taskStatus);
        } else {
            tasks = taskRepository.findAllByOrderByDueDateAscContractNoAscPeriodNoAsc();
        }

        return tasks.stream().map(CollectionTaskService::toView).toList();
    }

    private static CollectionTaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CollectionTaskStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleViolationException(
                    "非法催收任务状态: " + status + "，允许值: OPEN, CLOSED");
        }
    }

    private static CollectionTaskView toView(CollectionTask task) {
        return new CollectionTaskView(task.getContractNo(), task.getPeriodNo(),
                task.getDueDate(), task.getOverdueDays(), task.getOutstandingAmount(),
                task.getStatus().name());
    }
}
