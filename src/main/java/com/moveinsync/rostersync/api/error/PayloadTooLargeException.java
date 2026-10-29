package com.moveinsync.rostersync.api.error;

public final class PayloadTooLargeException extends ApiException {
    public PayloadTooLargeException() { super(ErrorCode.PAYLOAD_TOO_LARGE); }
}
