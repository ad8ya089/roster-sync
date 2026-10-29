package com.moveinsync.rostersync.domain.recon;

import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.model.EmploymentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BucketDiffer {
    private final RecordHasher hasher;
    public BucketDiffer(RecordHasher hasher) { this.hasher = hasher; }

    public BucketDiff diff(Map<String, FileRow> fileRows, List<DbRow> dbRows, Set<String> presentButInvalid, Instant asOf) {
        var actions = new ArrayList<PlannedAction>();
        var seen = new HashSet<String>();
        long active = 0, missing = 0, creates = 0, updates = 0, heals = 0, unchanged = 0, stale = 0;
        for (DbRow row : dbRows) {
            seen.add(row.employeeId());
            if (row.status() == EmploymentStatus.ACTIVE) active++;
            if (presentButInvalid.contains(row.employeeId())) continue;
            FileRow file = fileRows.get(row.employeeId());
            if (file == null) {
                if (row.status() == EmploymentStatus.ACTIVE && !row.hrUpdatedAt().isAfter(asOf)) {
                    actions.add(new PlannedAction(row.employeeId(), ActionType.DEACTIVATE, null, row.hrVersion(), null));
                    missing++;
                }
            } else if (file.hash().equals(hasher.hash(row.snapshot()))) {
                unchanged++;
            } else if (file.version() < row.hrVersion()) {
                stale++;
            } else {
                ActionType type = file.version() == row.hrVersion() ? ActionType.HEAL : ActionType.UPDATE;
                actions.add(new PlannedAction(row.employeeId(), type, file.version(), row.hrVersion(), file.snapshot()));
                if (type == ActionType.HEAL) heals++; else updates++;
            }
        }
        for (FileRow file : fileRows.values()) {
            if (!seen.contains(file.employeeId()) && !presentButInvalid.contains(file.employeeId())) {
                actions.add(new PlannedAction(file.employeeId(), ActionType.CREATE, file.version(), null, file.snapshot()));
                creates++;
            }
        }
        return new BucketDiff(actions, new DiffCounters(active, missing, creates, updates, heals, unchanged, stale));
    }
}
