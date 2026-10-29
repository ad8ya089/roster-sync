package com.moveinsync.rostersync.api.error;

public abstract class ApiException extends RuntimeException {
    private final ErrorCode errorCode;

    protected ApiException(ErrorCode errorCode) {
        super(errorCode.title());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
