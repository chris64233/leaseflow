package com.leaseflow.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementCorrectionRepository
        extends JpaRepository<SettlementCorrection, Long> {

    Optional<SettlementCorrection> findByCorrectionNo(String correctionNo);

    boolean existsByCorrectionNo(String correctionNo);

    List<SettlementCorrection> findBySettlementIdOrderBySeqNoAscIdAsc(Long settlementId);
}
