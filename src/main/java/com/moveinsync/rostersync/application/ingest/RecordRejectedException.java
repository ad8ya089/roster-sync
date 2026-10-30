package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.domain.validation.FieldError;
import java.util.List;

public final class RecordRejectedException extends RuntimeException {
    private final List<FieldError> errors;
    public RecordRejectedException(List<FieldError> errors) { super("RECORD_REJECTED"); this.errors = List.copyOf(errors); }
    public List<FieldError> errors() { return errors; }
}
