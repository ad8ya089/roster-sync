package com.moveinsync.rostersync.domain.command;

import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.time.Instant;
import java.util.Optional;

public sealed abstract class HrCommand permits CreateEmployee, UpdateEmployee, DeactivateEmployee {
    private final String employeeId;
    private final long version;
    private final String eventId;
    private final Instant occurredAt;

    protected HrCommand(String employeeId, long version, String eventId, Instant occurredAt) {
        this.employeeId = employeeId;
        this.version = version;
        this.eventId = eventId;
        this.occurredAt = occurredAt;
    }

    public final String employeeId() { return employeeId; }
    public final long version() { return version; }
    public final String eventId() { return eventId; }
    public final Instant occurredAt() { return occurredAt; }
    public abstract HrEventType type();
    public abstract HrSnapshot resolveSnapshot(Optional<HrSnapshot> current);

    @Override
    public final String toString() { return "HrCommand[type=" + type() + ", employeeId=" + employeeId + ", version=" + version + "]"; }
}
