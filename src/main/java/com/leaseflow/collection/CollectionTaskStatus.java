package com.leaseflow.collection;

/**
 * 催收任务状态。
 *
 * <p>OPEN：租金期次已到期且仍有未收金额；
 * CLOSED：租金期次已足额回款，催收任务在随后的扫描中关闭。
 */
public enum CollectionTaskStatus {
    OPEN,
    CLOSED
}
