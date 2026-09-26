package com.leaseflow.asset;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LeasedAssetRepository extends JpaRepository<LeasedAsset, Long> {

    boolean existsByAssetCode(String assetCode);

    Optional<LeasedAsset> findByAssetCode(String assetCode);

    /**
     * 按编码查询租赁物并加行级悲观写锁，用于在同一事务内串行化
     * 同一租赁物的并发残值评估登记。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from LeasedAsset asset where asset.assetCode = :assetCode")
    Optional<LeasedAsset> findByAssetCodeForUpdate(@Param("assetCode") String assetCode);
}
