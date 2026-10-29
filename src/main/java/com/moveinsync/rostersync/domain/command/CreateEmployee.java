package com.moveinsync.rostersync.domain.command;

import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class CreateEmployee extends HrCommand {
    private final HrSnapshot snapshot;

    public CreateEmployee(String employeeId, long version, String eventId, Instant occurredAt, HrSnapshot snapshot) {
        super(employeeId, version, eventId, occurredAt);
        Objects.requireNonNull(snapshot, "snapshot");
        this.snapshot = snapshot.status() == null ? snapshot.withStatus("ACTIVE") : snapshot;
    }

    @Override public HrEventType type() { return HrEventType.CREATE; }
    @Override public HrSnapshot resolveSnapshot(Optional<HrSnapshot> current) { return snapshot; }
}
