package com.moveinsync.rostersync.domain;

import com.moveinsync.rostersync.domain.model.*;
import java.time.Instant;

public final class DomainFixtures {
    public static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private DomainFixtures() { }
    public static HrSnapshot snapshot(String shift) {
        return new HrSnapshot("Test Person", "test@example.test", "+919800000001", "M1", "BLR-1", shift, "ACTIVE");
    }
    public static CurrentEmployeeState state(long version, HrSnapshot snapshot) {
        return new CurrentEmployeeState(true, version, EmploymentStatus.valueOf(snapshot.status()), snapshot, NOW);
    }
}
