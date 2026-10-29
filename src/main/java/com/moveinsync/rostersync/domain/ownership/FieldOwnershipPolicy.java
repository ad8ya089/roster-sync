package com.moveinsync.rostersync.domain.ownership;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.model.FieldOwner;
import com.moveinsync.rostersync.domain.model.WriteSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FieldOwnershipPolicy {
    private final Set<EmployeeField> transportOwned;

    public FieldOwnershipPolicy(Set<EmployeeField> transportOwned) {
        this.transportOwned = Set.copyOf(transportOwned);
    }

    public static FieldOwnershipPolicy defaults() {
        return new FieldOwnershipPolicy(EnumSet.of(EmployeeField.PICKUP_POINT, EmployeeField.SPECIAL_ASSISTANCE));
    }

    public FieldOwner ownerOf(EmployeeField field) {
        return transportOwned.contains(Objects.requireNonNull(field, "field")) ? FieldOwner.TRANSPORT : FieldOwner.HR;
    }

    public List<EmployeeField> hrFields() {
        return Arrays.stream(EmployeeField.values()).filter(field -> ownerOf(field) == FieldOwner.HR).toList();
    }

    public OwnershipFilterResult filter(WriteSource source, Map<EmployeeField, String> values) {
        EnumMap<EmployeeField, String> kept = new EnumMap<>(EmployeeField.class);
        List<EmployeeField> ignored = new ArrayList<>();
        for (EmployeeField field : EmployeeField.values()) {
            if (values.containsKey(field)) {
                if (ownerOf(field) == source.owner()) kept.put(field, values.get(field));
                else ignored.add(field);
            }
        }
        return new OwnershipFilterResult(kept, ignored);
    }
}
