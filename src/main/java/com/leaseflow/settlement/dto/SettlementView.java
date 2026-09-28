package com.leaseflow.settlement.dto;

import com.leaseflow.asset.AssetStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 残值结算完整视图：除结算结果外，包含冻结的完整计算依据（最新评估版本快照、
 * 实际处置情况、合同计算规则快照）、逐项明细以及历次更正（含更正后的有效结果）。
 */
public record SettlementView(
        String settlementNo,
        String assetCode,
        AssetStatus assetStatus,
        LocalDate settlementDate,
        Basis basis,
        SettlementAmountsView originalResult,
        SettlementAmountsView effectiveResult,
        List<SettlementItemView> items,
        List<SettlementCorrectionView> corrections
) {

    /**
     * 冻结的计算依据。
     */
    public record Basis(
            FrozenValuationView valuation,
            DisposalView disposal,
            FrozenContractRuleView contractRule
    ) {
    }
}
