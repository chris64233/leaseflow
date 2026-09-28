package com.leaseflow.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementItemRepository
        extends JpaRepository<SettlementItem, Long> {

    List<SettlementItem> findBySettlementIdOrderByLineNoAsc(Long settlementId);

    long countBySettlementId(Long settlementId);
}
