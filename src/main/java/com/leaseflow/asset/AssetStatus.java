package com.leaseflow.asset;

/**
 * 租赁物生命周期状态。
 *
 * <ul>
 *   <li>{@code IN_LEASE}：在租，租赁物尚未完成残值结算，可继续登记普通评估；</li>
 *   <li>{@code SETTLED}：残值已结算，资产状态、金额与明细已冻结落库，
 *       此后不再接受普通评估，修正只能走只追加、不覆盖原始依据的结算更正流程。</li>
 * </ul>
 */
public enum AssetStatus {
    IN_LEASE,
    SETTLED
}
