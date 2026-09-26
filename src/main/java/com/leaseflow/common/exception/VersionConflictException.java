package com.leaseflow.common.exception;

/**
 * 客户端提交所基于的版本已过期（乐观版本冲突），映射为 HTTP 409。
 */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(String message) {
        super(message);
    }
}
