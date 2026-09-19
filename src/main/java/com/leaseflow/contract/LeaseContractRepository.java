package com.leaseflow.contract;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeaseContractRepository extends JpaRepository<LeaseContract, Long> {

    boolean existsByContractNo(String contractNo);

    Optional<LeaseContract> findByContractNo(String contractNo);
}
