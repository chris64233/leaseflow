package com.leaseflow.asset;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeasedAssetRepository extends JpaRepository<LeasedAsset, Long> {

    boolean existsByAssetCode(String assetCode);
}
