package com.moveinsync.rostersync.domain.decision;

import com.moveinsync.rostersync.domain.command.HrCommand;
import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.outbox.OutboxEventType;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ChangeDecider {
    private final FieldOwnershipPolicy ownership;
    private final RecordHasher hasher;

    public ChangeDecider(FieldOwnershipPolicy ownership) {
        this(ownership, new RecordHasher(ownership));
    }

    public ChangeDecider(FieldOwnershipPolicy ownership, RecordHasher hasher) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public ApplyDecision decide(CurrentEmployeeState current, HrCommand command, DecisionMode mode) {
        if (current.exists() && command.version() < current.hrVersion()) return new ApplyDecision.Stale(current.hrVersion());
        HrSnapshot incoming = command.resolveSnapshot(Optional.ofNullable(current.snapshot()));
        HrSnapshot merged = mergeOwnedFields(current.snapshot(), incoming);
        List<EmployeeField> changed = ownership.hrFields().stream()
                .filter(field -> !Objects.equals(canonical(current.snapshot(), field), canonical(merged, field))).toList();
        boolean same = current.exists() && hasher.hash(current.snapshot()).equals(hasher.hash(merged));
        boolean equalVersion = current.exists() && command.version() == current.hrVersion();
        if (equalVersion && same) return new ApplyDecision.Duplicate();
        if (equalVersion && mode == DecisionMode.EVENT) return new ApplyDecision.Conflict(current.hrVersion());
        // Preserve the source high-water mark even when its business state has not changed.
        // This has no change event; otherwise a late intermediate CREATE could revive a tombstone.
        if (same) return new ApplyDecision.VersionAdvanced(command.version());
        assert !changed.isEmpty() : "Different HR hashes must have changed HR fields";
        return new ApplyDecision.Apply(merged, changed, eventType(current, merged), equalVersion && !same);
    }

    private String canonical(HrSnapshot snapshot, EmployeeField field) {
        return snapshot == null ? null : field.canonicalValue(snapshot.get(field));
    }

    private boolean isTombstone(CurrentEmployeeState current) {
        return current.status() == EmploymentStatus.INACTIVE && ownership.hrFields().stream()
                .filter(field -> field != EmployeeField.STATUS).allMatch(field -> current.snapshot().get(field) == null);
    }

    private OutboxEventType eventType(CurrentEmployeeState current, HrSnapshot incoming) {
        EmploymentStatus nextStatus = EmploymentStatus.valueOf(incoming.status());
        if (!current.exists() || (isTombstone(current) && nextStatus == EmploymentStatus.ACTIVE)) return OutboxEventType.CREATED;
        if (current.status() != nextStatus) return nextStatus == EmploymentStatus.INACTIVE ? OutboxEventType.DEACTIVATED : OutboxEventType.REACTIVATED;
        return OutboxEventType.UPDATED;
    }

    private HrSnapshot mergeOwnedFields(HrSnapshot current, HrSnapshot incoming) {
        var fields = new EnumMap<EmployeeField, String>(EmployeeField.class);
        for (EmployeeField field : EmployeeField.values()) {
            fields.put(field, ownership.ownerOf(field) == FieldOwner.HR ? incoming.get(field) : current == null ? null : current.get(field));
        }
        return new HrSnapshot(fields.get(EmployeeField.NAME), fields.get(EmployeeField.EMAIL), fields.get(EmployeeField.PHONE),
                fields.get(EmployeeField.MANAGER_ID), fields.get(EmployeeField.OFFICE_CODE), fields.get(EmployeeField.SHIFT_CODE),
                fields.get(EmployeeField.STATUS), fields.get(EmployeeField.PICKUP_POINT), fields.get(EmployeeField.SPECIAL_ASSISTANCE));
    }
}
