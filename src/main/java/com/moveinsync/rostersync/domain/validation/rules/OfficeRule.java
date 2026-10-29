package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.List;

public final class OfficeRule implements ValidationRule<ValidationInput> {
    private final OfficeLookup offices;
    public OfficeRule(OfficeLookup offices) { this.offices = offices; }
    @Override public List<FieldError> validate(ValidationInput input) {
        String code = input.get(EmployeeField.OFFICE_CODE);
        if (code == null || code.isBlank()) return List.of();
        return switch (offices.lookup(input.tenantId(), code)) {
            case ACTIVE -> List.of();
            case INACTIVE -> List.of(new FieldError("officeCode", "INACTIVE_OFFICE"));
            case UNKNOWN -> List.of(new FieldError("officeCode", "UNKNOWN_OFFICE"));
        };
    }
}
