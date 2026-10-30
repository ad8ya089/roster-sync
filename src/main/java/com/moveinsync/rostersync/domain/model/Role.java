package com.moveinsync.rostersync.domain.model;

public enum Role {
    HR_SYSTEM, TRANSPORT_ADMIN, OPERATOR, EMPLOYEE;

    public boolean hasRequiredEmployeeId(String employeeId) {
        return this != EMPLOYEE || (employeeId != null && !employeeId.isBlank() && employeeId.length() <= 64);
    }
}
