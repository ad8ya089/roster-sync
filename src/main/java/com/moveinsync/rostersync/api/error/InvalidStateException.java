package com.moveinsync.rostersync.api.error;

public final class InvalidStateException extends ApiException {
    public InvalidStateException() { super(ErrorCode.INVALID_RUN_STATE); }
}
