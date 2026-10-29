package com.moveinsync.rostersync.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record TransportFields(Map<EmployeeField, String> values) {
    public TransportFields {
        EnumMap<EmployeeField, String> copy = new EnumMap<>(EmployeeField.class);
        copy.putAll(values);
        values = Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() { return "TransportFields[fields=" + values.keySet() + "]"; }
}
