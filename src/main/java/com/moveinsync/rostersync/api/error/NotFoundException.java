package com.moveinsync.rostersync.api.error;

public final class NotFoundException extends ApiException {
    public NotFoundException() { super(ErrorCode.NOT_FOUND); }
}
