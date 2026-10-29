package com.moveinsync.rostersync.domain.decision;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import com.moveinsync.rostersync.domain.outbox.OutboxEventType;
import java.util.List;

public sealed interface ApplyDecision {
    record Apply(HrSnapshot newSnapshot, List<EmployeeField> changedFields, OutboxEventType eventType,
                 boolean heal) implements ApplyDecision {
        public Apply { changedFields = List.copyOf(changedFields); }
    }
    record Stale(long currentVersion) implements ApplyDecision { }
    record Duplicate() implements ApplyDecision { }
    record Conflict(long currentVersion) implements ApplyDecision { }
}
