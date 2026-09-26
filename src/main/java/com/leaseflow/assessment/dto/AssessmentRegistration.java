package com.leaseflow.assessment.dto;

/**
 * 评估登记结果：created 为 true 表示追加了新版本（201），
 * false 表示命中幂等重放、返回首次登记结果（200）。
 */
public record AssessmentRegistration(AssessmentView assessment, boolean created) {
}
