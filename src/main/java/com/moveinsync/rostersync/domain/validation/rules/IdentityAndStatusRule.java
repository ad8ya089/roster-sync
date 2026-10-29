package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class IdentityAndStatusRule implements ValidationRule<ValidationInput> {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SHIFT = Pattern.compile("[A-Z0-9_-]{1,20}");

    @Override public List<FieldError> validate(ValidationInput input) {
        var errors = new ArrayList<FieldError>();
        if (input.version() <= 0) errors.add(new FieldError("version", "INVALID_VERSION"));
        if (!Set.of("CREATE", "UPDATE", "DEACTIVATE").contains(input.eventType() == null ? "" : input.eventType())) errors.add(new FieldError("type", "INVALID_EVENT_TYPE"));
        if (input.employeeId() != null && !input.employeeId().isBlank() && !IDENTIFIER.matcher(input.employeeId()).matches()) errors.add(new FieldError("employeeId", "INVALID_FORMAT"));
        String manager = input.get(EmployeeField.MANAGER_ID);
        if (manager != null && !IDENTIFIER.matcher(manager).matches()) errors.add(new FieldError("managerId", "INVALID_FORMAT"));
        if (manager != null && manager.equals(input.employeeId())) errors.add(new FieldError("managerId", "MANAGER_SELF_REFERENCE"));
        String shift = input.get(EmployeeField.SHIFT_CODE);
        if (shift != null && !SHIFT.matcher(shift).matches()) errors.add(new FieldError("shiftCode", "INVALID_FORMAT"));
        String status = input.get(EmployeeField.STATUS);
        if (status != null && (!status.isBlank() || "CREATE".equals(input.eventType())) && !Set.of("ACTIVE", "INACTIVE").contains(status)) errors.add(new FieldError("status", "INVALID_STATUS"));
        return errors;
    }
}
