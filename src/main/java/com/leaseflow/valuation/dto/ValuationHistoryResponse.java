package com.leaseflow.valuation.dto;

import java.util.List;

/**
 * 资产评估历史：版本按版本号升序稳定排列，{@code latestVersion} 为当前最新版本号
 * （尚无评估时为 0）。
 */
public record ValuationHistoryResponse(
        String assetCode,
        int latestVersion,
        List<ValuationView> valuations
) {
}
