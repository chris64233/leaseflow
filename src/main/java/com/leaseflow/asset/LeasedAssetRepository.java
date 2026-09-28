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
     * 对租赁物行加悲观写锁，串行化同一资产上的并发操作：
     * 残值评估登记与残值结算确认共用此锁，因此新评估与结算并发时只有一方能基于
     * 其进入锁时看到的最新状态成功，另一方在锁内重新判定后得到 409。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from LeasedAsset asset where asset.assetCode = :assetCode")
    Optional<LeasedAsset> findByAssetCodeForUpdate(@Param("assetCode") String assetCode);
}
