package com.moveinsync.rostersync.application.ingest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.rostersync.domain.model.IngestOutcome;
import com.moveinsync.rostersync.domain.validation.FieldError;
import java.util.List;

public record IngestResult(Summary summary, List<RecordResult> results) {
    public record Summary(int received, long applied, long noChange, long duplicate, long stale, long conflict, long rejected, long retryableError) { }
    public record RecordResult(int index, String employeeId, long version, IngestOutcome outcome,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Long currentVersion,
                               @JsonInclude(JsonInclude.Include.NON_NULL) IngestOutcome originalOutcome,
                               List<FieldError> warnings, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> errors) { }
}
