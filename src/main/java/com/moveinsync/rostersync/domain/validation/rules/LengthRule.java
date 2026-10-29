package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.ArrayList;
import java.util.List;

public final class LengthRule implements ValidationRule<ValidationInput> {
    @Override public List<FieldError> validate(ValidationInput input) {
        var errors = new ArrayList<FieldError>();
        if (input.employeeId() != null && input.employeeId().length() > 64) errors.add(new FieldError("employeeId", "TOO_LONG"));
        for (EmployeeField field : EmployeeField.values()) {
            String value = input.get(field);
            if (value != null && value.length() > field.maximumLength()) errors.add(new FieldError(field.jsonName(), "TOO_LONG"));
        }
        return errors;
    }
}
