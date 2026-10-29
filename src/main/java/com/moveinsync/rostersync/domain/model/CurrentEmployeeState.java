package com.moveinsync.rostersync.domain.model;

import java.time.Instant;
import java.util.Objects;

public record CurrentEmployeeState(boolean exists, long hrVersion, EmploymentStatus status,
                                   HrSnapshot snapshot, Instant hrUpdatedAt) {
    public CurrentEmployeeState {
        if (exists) {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(hrUpdatedAt, "hrUpdatedAt");
            if (hrVersion < 1 || !status.name().equals(snapshot.status())) {
                throw new IllegalArgumentException("Invalid current employee state");
            }
        } else if (hrVersion != 0 || status != null || snapshot != null || hrUpdatedAt != null) {
            throw new IllegalArgumentException("Missing employee must have no stored state");
        }
    }

    public static CurrentEmployeeState missing() {
        return new CurrentEmployeeState(false, 0, null, null, null);
    }
}
