package com.leaseflow.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResidualAssessmentRepository extends JpaRepository<ResidualAssessment, Long> {

    Optional<ResidualAssessment> findByAssessmentNo(String assessmentNo);

    List<ResidualAssessment> findByAssetIdOrderByVersionAsc(Long assetId);

    Optional<ResidualAssessment> findTopByAssetIdOrderByVersionDesc(Long assetId);

    long countByAssetId(Long assetId);
}
