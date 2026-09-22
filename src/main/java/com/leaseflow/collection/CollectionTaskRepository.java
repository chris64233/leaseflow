package com.leaseflow.collection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CollectionTaskRepository extends JpaRepository<CollectionTask, Long> {

    Optional<CollectionTask> findByScheduleItemId(Long scheduleItemId);

    List<CollectionTask> findByScheduleItemIdIn(List<Long> scheduleItemIds);

    List<CollectionTask> findByContractNoOrderByDueDateAscPeriodNoAsc(String contractNo);

    List<CollectionTask> findByContractNoAndStatusOrderByDueDateAscPeriodNoAsc(
            String contractNo, CollectionTaskStatus status);

    List<CollectionTask> findByStatusOrderByDueDateAscContractNoAscPeriodNoAsc(
            CollectionTaskStatus status);

    List<CollectionTask> findAllByOrderByDueDateAscContractNoAscPeriodNoAsc();
}
