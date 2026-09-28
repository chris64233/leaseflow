package com.leaseflow.asset;

/**
 * 租赁物生命周期状态。
 *
 * <p>IN_SERVICE：租赁中；SETTLED：残值已结算（终态）。
 * 资产一旦进入 SETTLED，不再接受普通残值评估，修正只能通过结算更正流程追加。
 */
public enum AssetStatus {
    IN_SERVICE,
    SETTLED
}
