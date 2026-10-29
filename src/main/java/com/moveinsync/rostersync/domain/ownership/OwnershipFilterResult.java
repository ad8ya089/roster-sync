package com.moveinsync.rostersync.domain.ownership;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record OwnershipFilterResult(Map<EmployeeField, String> kept, List<EmployeeField> ignoredNotOwned) {
    public OwnershipFilterResult {
        EnumMap<EmployeeField, String> copy = new EnumMap<>(EmployeeField.class);
        copy.putAll(kept);
        kept = Collections.unmodifiableMap(copy);
        ignoredNotOwned = List.copyOf(ignoredNotOwned);
    }

    @Override
    public String toString() {
        return "OwnershipFilterResult[keptFields=" + kept.keySet() + ", ignoredNotOwned=" + ignoredNotOwned + "]";
    }
}
