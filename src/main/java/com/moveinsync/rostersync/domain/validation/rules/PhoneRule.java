package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.List;
import java.util.regex.Pattern;

public final class PhoneRule implements ValidationRule<ValidationInput> {
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");
    @Override public List<FieldError> validate(ValidationInput input) {
        String phone = input.get(EmployeeField.PHONE);
        return phone == null || E164.matcher(phone).matches() ? List.of() : List.of(new FieldError("phone", "INVALID_FORMAT"));
    }
}
