package com.moveinsync.rostersync.domain.decision;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import com.moveinsync.rostersync.domain.model.IngestOutcome;
import com.moveinsync.rostersync.domain.outbox.OutboxEventType;
import java.util.List;

public sealed interface ApplyDecision {
    record Apply(HrSnapshot newSnapshot, List<EmployeeField> changedFields, OutboxEventType eventType,
                 boolean heal) implements ApplyDecision {
        public Apply {
            changedFields = List.copyOf(changedFields);
            if (changedFields.isEmpty()) throw new IllegalArgumentException("Apply requires changed HR fields");
        }
    }
    record VersionAdvanced(long newVersion) implements ApplyDecision { }
    record Stale(long currentVersion) implements ApplyDecision { }
    record Duplicate() implements ApplyDecision { }
    record Conflict(long currentVersion) implements ApplyDecision { }

    default IngestOutcome outcome() {
        return switch (this) {
            case Apply ignored -> IngestOutcome.APPLIED;
            case VersionAdvanced ignored -> IngestOutcome.NO_CHANGE;
            case Stale ignored -> IngestOutcome.STALE;
            case Duplicate ignored -> IngestOutcome.DUPLICATE;
            case Conflict ignored -> IngestOutcome.CONFLICT;
        };
    }
}
