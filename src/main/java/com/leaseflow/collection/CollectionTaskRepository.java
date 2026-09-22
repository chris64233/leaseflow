package com.leaseflow.collection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CollectionTaskRepository extends JpaRepository<CollectionTask, Long> {

    Optional<CollectionTask> findByScheduleItemId(Long scheduleItemId);

    List<CollectionTask> findAllByOrderByScheduleItemDueDateAscScheduleItemContractContractNoAscScheduleItemPeriodNoAscIdAsc();

    List<CollectionTask> findByStatusOrderByScheduleItemDueDateAscScheduleItemContractContractNoAscScheduleItemPeriodNoAscIdAsc(
            CollectionTaskStatus status);

    List<CollectionTask> findByScheduleItemContractContractNoOrderByScheduleItemDueDateAscScheduleItemPeriodNoAscIdAsc(
            String contractNo);

    List<CollectionTask> findByScheduleItemContractContractNoAndStatusOrderByScheduleItemDueDateAscScheduleItemPeriodNoAscIdAsc(
            String contractNo, CollectionTaskStatus status);
}
