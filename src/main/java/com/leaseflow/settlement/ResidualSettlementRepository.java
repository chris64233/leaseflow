package com.leaseflow.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResidualSettlementRepository extends JpaRepository<ResidualSettlement, Long> {

    Optional<ResidualSettlement> findBySettlementNo(String settlementNo);

    Optional<ResidualSettlement> findByAssetId(Long assetId);

    boolean existsByAssetId(Long assetId);
}
