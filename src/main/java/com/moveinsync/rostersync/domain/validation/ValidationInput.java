package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.model.WriteSource;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public record ValidationInput(UUID tenantId, String employeeId, long version, String eventType,
                              WriteSource source, Map<EmployeeField, String> fields) {
    public ValidationInput {
        var copy = new EnumMap<EmployeeField, String>(EmployeeField.class);
        copy.putAll(fields);
        if (copy.containsKey(EmployeeField.EMAIL)) copy.put(EmployeeField.EMAIL, EmployeeField.EMAIL.canonicalValue(copy.get(EmployeeField.EMAIL)));
        fields = Collections.unmodifiableMap(copy);
    }
    public String get(EmployeeField field) { return fields.get(field); }
    @Override public String toString() { return "ValidationInput[employeeId=" + employeeId + ", version=" + version + ", fields=" + fields.keySet() + "]"; }
}
