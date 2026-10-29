package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.ArrayList;
import java.util.List;

public final class RequiredFieldsRule implements ValidationRule<ValidationInput> {
    private final FieldOwnershipPolicy ownership;
    public RequiredFieldsRule(FieldOwnershipPolicy ownership) { this.ownership = ownership; }

    @Override public List<FieldError> validate(ValidationInput input) {
        var errors = new ArrayList<FieldError>();
        if (input.employeeId() == null || input.employeeId().isBlank()) errors.add(new FieldError("employeeId", "MISSING_REQUIRED_FIELD"));
        boolean file = input.source() == WriteSource.HR_SNAPSHOT;
        boolean fullState = "CREATE".equals(input.eventType()) || "UPDATE".equals(input.eventType());
        if (fullState && (!file || "ACTIVE".equals(input.get(EmployeeField.STATUS)))) {
            for (EmployeeField field : List.of(EmployeeField.NAME, EmployeeField.EMAIL, EmployeeField.OFFICE_CODE)) {
                if (ownership.ownerOf(field) == FieldOwner.HR && missing(input.get(field))) errors.add(new FieldError(field.jsonName(), "MISSING_REQUIRED_FIELD"));
            }
        }
        if ((file || "UPDATE".equals(input.eventType())) && ownership.ownerOf(EmployeeField.STATUS) == FieldOwner.HR && missing(input.get(EmployeeField.STATUS))) {
            errors.add(new FieldError("status", "MISSING_REQUIRED_FIELD"));
        }
        return errors;
    }
    private boolean missing(String value) { return value == null || value.isBlank(); }
}
