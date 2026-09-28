package com.leaseflow.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "leased_asset", uniqueConstraints = {
        @UniqueConstraint(name = "uk_leased_asset_code", columnNames = "asset_code")
})
public class LeasedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_code", nullable = false, length = 64)
    private String assetCode;

    @Column(name = "asset_name", nullable = false, length = 128)
    private String assetName;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "original_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssetStatus status = AssetStatus.IN_SERVICE;

    protected LeasedAsset() {
    }

    public LeasedAsset(String assetCode, String assetName, String category, BigDecimal originalValue) {
        this.assetCode = assetCode;
        this.assetName = assetName;
        this.category = category;
        this.originalValue = originalValue;
    }

    public AssetStatus getStatus() {
        return status;
    }

    /**
     * 进入残值结算终态。结算成功后状态不可再改变，只能通过结算更正追加修正。
     */
    public void markSettled() {
        this.status = AssetStatus.SETTLED;
    }

    public Long getId() {
        return id;
    }

    public String getAssetCode() {
        return assetCode;
    }

    public String getAssetName() {
        return assetName;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getOriginalValue() {
        return originalValue;
    }
}
