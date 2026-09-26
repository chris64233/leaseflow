package com.leaseflow.valuation.dto;

/**
 * 登记结果：{@code replayed} 为 true 时表示命中相同编号、相同内容的重复提交，
 * 返回的是首次登记结果而非新版本。
 */
public record ValuationRegistrationResult(ValuationView valuation, boolean replayed) {
}
