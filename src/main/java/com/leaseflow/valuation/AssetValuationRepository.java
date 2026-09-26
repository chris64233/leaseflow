package com.leaseflow.valuation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetValuationRepository extends JpaRepository<AssetValuation, Long> {

    Optional<AssetValuation> findByValuationNo(String valuationNo);

    boolean existsByValuationNo(String valuationNo);

    Optional<AssetValuation> findTopByAssetIdOrderByVersionNoDesc(Long assetId);

    List<AssetValuation> findByAssetIdOrderByVersionNoAscIdAsc(Long assetId);
}
