package com.moveinsync.rostersync.domain.decision;

import com.moveinsync.rostersync.domain.command.HrCommand;
import com.moveinsync.rostersync.domain.command.HrEventType;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.outbox.OutboxEventType;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ChangeDecider {
    private final FieldOwnershipPolicy ownership;

    public ChangeDecider(FieldOwnershipPolicy ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public ApplyDecision decide(CurrentEmployeeState current, HrCommand command, DecisionMode mode) {
        if (current.exists() && command.version() < current.hrVersion()) return new ApplyDecision.Stale(current.hrVersion());
        HrSnapshot incoming = command.resolveSnapshot(Optional.ofNullable(current.snapshot()));
        HrSnapshot merged = mergeOwnedFields(current.snapshot(), incoming);
        List<EmployeeField> changed = ownership.hrFields().stream()
                .filter(field -> !Objects.equals(canonical(current.snapshot(), field), canonical(merged, field))).toList();
        boolean same = current.exists() && changed.isEmpty();
        boolean equalVersion = current.exists() && command.version() == current.hrVersion();
        if (equalVersion && same) return new ApplyDecision.Duplicate();
        if (equalVersion && mode == DecisionMode.EVENT) return new ApplyDecision.Conflict(current.hrVersion());
        // Repeated deactivation of an empty tombstone is the explicitly requested duplicate-equivalent no-op.
        if (current.exists() && command.type() == HrEventType.DEACTIVATE && isTombstone(current)) {
            return new ApplyDecision.Duplicate();
        }
        // Higher versions with identical values still apply: otherwise an older event could overwrite them.
        assert !changed.isEmpty() || same || ownership.hrFields().isEmpty();
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
