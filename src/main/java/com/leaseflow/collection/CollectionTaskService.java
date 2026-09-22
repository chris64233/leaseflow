package com.leaseflow.collection;

import com.leaseflow.collection.dto.CollectionTaskView;
import com.leaseflow.collection.dto.OverdueScanResponse;
import com.leaseflow.schedule.PaymentScheduleItem;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CollectionTaskService {

    private final PaymentScheduleItemRepository scheduleItemRepository;
    private final CollectionTaskRepository taskRepository;

    public CollectionTaskService(PaymentScheduleItemRepository scheduleItemRepository,
                                 CollectionTaskRepository taskRepository) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.taskRepository = taskRepository;
    }

    /**
     * 按业务日期执行逾期扫描。
     *
     * <p>到期日早于业务日期且仍有未收金额的期次：无任务则新建 OPEN 催收任务，
     * 已有 OPEN 任务则刷新逾期天数与未收金额；已足额回款期次对应的 OPEN 任务在本次扫描中关闭。
     * 整个扫描（含行级悲观锁与任务变更）在同一事务内完成。
     */
    @Transactional
    public OverdueScanResponse scanOverdue(LocalDate businessDate) {
        List<PaymentScheduleItem> candidates =
                scheduleItemRepository.findOverdueCandidatesForUpdate(businessDate);

        long createdCount = 0;
        long updatedCount = 0;
        long closedCount = 0;
        List<CollectionTask> involvedTasks = new ArrayList<>();
        List<CollectionTask> newTasks = new ArrayList<>();

        for (PaymentScheduleItem item : candidates) {
            BigDecimal outstanding = item.getTotalDue().subtract(item.getPaidAmount());
            CollectionTask task = taskRepository.findByScheduleItemId(item.getId()).orElse(null);

            if (outstanding.signum() > 0) {
                int overdueDays = (int) ChronoUnit.DAYS.between(item.getDueDate(), businessDate);
                if (task == null) {
                    task = new CollectionTask(item, overdueDays, outstanding);
                    newTasks.add(task);
                    createdCount++;
                    involvedTasks.add(task);
                } else if (task.getStatus() == CollectionTaskStatus.OPEN
                        && (task.getOverdueDays() != overdueDays
                        || task.getOutstandingAmount().compareTo(outstanding) != 0)) {
                    task.refresh(overdueDays, outstanding);
                    updatedCount++;
                    involvedTasks.add(task);
                } else {
                    involvedTasks.add(task);
                }
            } else if (task != null && task.getStatus() == CollectionTaskStatus.OPEN) {
                int overdueDays = (int) ChronoUnit.DAYS.between(item.getDueDate(), businessDate);
                task.refresh(overdueDays, BigDecimal.ZERO.setScale(2));
                task.close();
                closedCount++;
                involvedTasks.add(task);
            } else if (task != null) {
                involvedTasks.add(task);
            }
        }

        if (!newTasks.isEmpty()) {
            taskRepository.saveAll(newTasks);
        }
        taskRepository.flush();

        List<CollectionTaskView> views = involvedTasks.stream()
                .map(this::toView)
                .sorted(Comparator
                        .comparing(CollectionTaskView::dueDate)
                        .thenComparing(CollectionTaskView::contractNo)
                        .thenComparingInt(CollectionTaskView::periodNo))
                .toList();

        return new OverdueScanResponse(businessDate, createdCount, updatedCount,
                closedCount, views);
    }

    /**
     * 按合同编号与状态查询催收任务；结果按到期日、合同编号、期次稳定排序。
     */
    @Transactional(readOnly = true)
    public List<CollectionTaskView> queryTasks(String contractNo, CollectionTaskStatus status) {
        boolean hasContractNo = contractNo != null && !contractNo.isBlank();
        List<CollectionTask> tasks;
        if (hasContractNo && status != null) {
            tasks = taskRepository
                    .findByScheduleItemContractContractNoAndStatusOrderByScheduleItemDueDateAscScheduleItemPeriodNoAscIdAsc(
                            contractNo, status);
        } else if (hasContractNo) {
            tasks = taskRepository
                    .findByScheduleItemContractContractNoOrderByScheduleItemDueDateAscScheduleItemPeriodNoAscIdAsc(
                            contractNo);
        } else if (status != null) {
            tasks = taskRepository
                    .findByStatusOrderByScheduleItemDueDateAscScheduleItemContractContractNoAscScheduleItemPeriodNoAscIdAsc(
                            status);
        } else {
            tasks = taskRepository
                    .findAllByOrderByScheduleItemDueDateAscScheduleItemContractContractNoAscScheduleItemPeriodNoAscIdAsc();
        }
        return tasks.stream().map(this::toView).toList();
    }

    private CollectionTaskView toView(CollectionTask task) {
        PaymentScheduleItem item = task.getScheduleItem();
        return new CollectionTaskView(item.getContract().getContractNo(), item.getPeriodNo(),
                item.getDueDate(), task.getOverdueDays(), task.getOutstandingAmount(),
                task.getStatus().name());
    }
}
