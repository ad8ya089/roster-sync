package com.moveinsync.rostersync.domain.command;

import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class UpdateEmployee extends HrCommand {
    private final HrSnapshot snapshot;

    public UpdateEmployee(String employeeId, long version, String eventId, Instant occurredAt, HrSnapshot snapshot) {
        super(employeeId, version, eventId, occurredAt);
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override public HrEventType type() { return HrEventType.UPDATE; }
    @Override public HrSnapshot resolveSnapshot(Optional<HrSnapshot> current) { return snapshot; }
}
