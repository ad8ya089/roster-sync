package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.domain.model.IngestOutcome;

public record ApplyResult(IngestOutcome outcome, Long currentVersion, IngestOutcome originalOutcome) {
    public static ApplyResult outcome(IngestOutcome outcome) { return new ApplyResult(outcome, null, null); }
}
