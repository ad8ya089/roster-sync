package com.moveinsync.rostersync.api.error;
import com.moveinsync.rostersync.domain.validation.FieldError;
import java.util.List;

public final class RequestValidationException extends ApiException {
    private final List<FieldError> errors;
    public RequestValidationException(ErrorCode code, List<FieldError> errors) { super(code); this.errors = List.copyOf(errors); }
    public List<FieldError> errors() { return errors; }
}
