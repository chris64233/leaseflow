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
     * 对租赁物行加悲观写锁，串行化同一资产的并发评估登记：
     * 后到事务在锁内重新读取最新版本，基于旧版本的请求将得到 409。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from LeasedAsset asset where asset.id = :assetId")
    Optional<LeasedAsset> findByIdForUpdate(@Param("assetId") Long assetId);
}
