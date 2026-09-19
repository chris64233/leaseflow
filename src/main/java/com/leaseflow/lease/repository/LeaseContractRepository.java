package com.leaseflow.lease.repository;

import com.leaseflow.lease.domain.LeaseContract;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeaseContractRepository extends JpaRepository<LeaseContract, Long> {

    boolean existsByContractNo(String contractNo);

    @EntityGraph(attributePaths = {"asset", "scheduleItems"})
    Optional<LeaseContract> findByContractNo(String contractNo);
}
