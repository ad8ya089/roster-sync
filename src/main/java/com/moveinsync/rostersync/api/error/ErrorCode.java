package com.moveinsync.rostersync.api.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "Malformed JSON"),
    BATCH_TOO_LARGE(HttpStatus.BAD_REQUEST, "Batch too large"),
    INVALID_CSV_HEADER(HttpStatus.BAD_REQUEST, "Invalid CSV header"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    OFFICE_CODE_EXISTS(HttpStatus.CONFLICT, "Office code already exists"),
    RECON_RUN_ALREADY_ACTIVE(HttpStatus.CONFLICT, "Reconciliation already active"),
    RECON_EXPORT_STALE(HttpStatus.CONFLICT, "Reconciliation export is stale"),
    INVALID_RUN_STATE(HttpStatus.CONFLICT, "Invalid run state"),
    INVALID_STATE(HttpStatus.CONFLICT, "Invalid state"),
    INVALID_OUTBOX_STATE(HttpStatus.CONFLICT, "Invalid outbox state"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    EMPLOYEE_INACTIVE(HttpStatus.CONFLICT, "Employee inactive"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dependency unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    MISSING_REQUIRED_FIELD(HttpStatus.BAD_REQUEST, "Required field missing"),
    INVALID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid format"),
    TOO_LONG(HttpStatus.BAD_REQUEST, "Field too long"),
    UNKNOWN_OFFICE(HttpStatus.BAD_REQUEST, "Unknown office"),
    INACTIVE_OFFICE(HttpStatus.BAD_REQUEST, "Inactive office"),
    INVALID_VERSION(HttpStatus.BAD_REQUEST, "Invalid version"),
    INVALID_STATUS(HttpStatus.BAD_REQUEST, "Invalid status"),
    INVALID_EVENT_TYPE(HttpStatus.BAD_REQUEST, "Invalid event type"),
    MANAGER_SELF_REFERENCE(HttpStatus.BAD_REQUEST, "Manager self reference"),
    UNKNOWN_FIELD(HttpStatus.BAD_REQUEST, "Unknown field"),
    DUPLICATE_ROW_IN_FILE(HttpStatus.BAD_REQUEST, "Duplicate file row"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "Version conflict"),
    RETRYABLE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "Retry required"),
    UNPROCESSABLE_RECORD(HttpStatus.BAD_REQUEST, "Record could not be processed"),
    FIELD_NOT_OWNED_BY_SOURCE(HttpStatus.BAD_REQUEST, "Field not owned by source"),
    UNKNOWN_FIELD_IGNORED(HttpStatus.BAD_REQUEST, "Unknown field ignored");

    private final HttpStatus httpStatus;
    private final String title;

    ErrorCode(HttpStatus httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public HttpStatus httpStatus() { return httpStatus; }
    public String title() { return title; }
}
