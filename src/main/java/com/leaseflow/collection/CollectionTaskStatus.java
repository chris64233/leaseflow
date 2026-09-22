package com.leaseflow.collection;

/**
 * 催收任务状态。
 *
 * <p>OPEN：催收中，对应租金期次仍有未收金额；
 * CLOSED：已关闭，对应租金期次已足额回款。
 */
public enum CollectionTaskStatus {
    OPEN,
    CLOSED
}
