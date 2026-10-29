package com.moveinsync.rostersync.domain.command;

import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.time.Instant;
import java.util.Optional;

public final class DeactivateEmployee extends HrCommand {
    public DeactivateEmployee(String employeeId, long version, String eventId, Instant occurredAt) {
        super(employeeId, version, eventId, occurredAt);
    }

    @Override public HrEventType type() { return HrEventType.DEACTIVATE; }
    @Override public HrSnapshot resolveSnapshot(Optional<HrSnapshot> current) {
        return current.orElseGet(HrSnapshot::tombstone).withStatus("INACTIVE");
    }
}
