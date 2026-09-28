package com.leaseflow.settlement.dto;

/**
 * 结算确认结果：{@code replayed} 为 true 时表示命中相同结算编号的幂等重试，
 * 返回首次结算结果（HTTP 200）；否则为本次新建（HTTP 201）。
 */
public record SettlementRegistrationResult(SettlementView settlement, boolean replayed) {
}
