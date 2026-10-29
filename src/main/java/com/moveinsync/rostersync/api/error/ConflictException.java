package com.moveinsync.rostersync.api.error;

import org.springframework.http.HttpStatus;

public final class ConflictException extends ApiException {
    public ConflictException(ErrorCode code) {
        super(requireConflict(code));
    }

    private static ErrorCode requireConflict(ErrorCode code) {
        if (code.httpStatus() != HttpStatus.CONFLICT) {
            throw new IllegalArgumentException("ConflictException requires a conflict error code");
        }
        return code;
    }
}
