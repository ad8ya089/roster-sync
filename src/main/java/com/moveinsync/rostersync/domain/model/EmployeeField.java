package com.moveinsync.rostersync.domain.model;

import java.util.Locale;

public enum EmployeeField {
    NAME("name", "name", 200),
    EMAIL("email", "email", 254),
    PHONE("phone", "phone", 20),
    MANAGER_ID("managerId", "manager_id", 64),
    OFFICE_CODE("officeCode", "office_code", 32),
    SHIFT_CODE("shiftCode", "shift_code", 20),
    STATUS("status", "status", 10),
    PICKUP_POINT("pickupPoint", "pickup_point", 300),
    SPECIAL_ASSISTANCE("specialAssistance", "special_assistance", 300);

    private final String jsonName;
    private final String csvName;
    private final int maximumLength;

    EmployeeField(String jsonName, String csvName, int maximumLength) {
        this.jsonName = jsonName;
        this.csvName = csvName;
        this.maximumLength = maximumLength;
    }

    public String jsonName() { return jsonName; }
    public String csvName() { return csvName; }
    public int maximumLength() { return maximumLength; }

    public String canonicalValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return this == EMAIL ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }
}
