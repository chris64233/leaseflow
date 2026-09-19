package com.leaseflow.lease.repository;

import com.leaseflow.lease.domain.LeasedAsset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeasedAssetRepository extends JpaRepository<LeasedAsset, Long> {

    boolean existsByAssetCode(String assetCode);
}
