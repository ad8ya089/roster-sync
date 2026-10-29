package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.List;
import java.util.regex.Pattern;

public final class EmailRule implements ValidationRule<ValidationInput> {
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    @Override public List<FieldError> validate(ValidationInput input) {
        String email = input.get(EmployeeField.EMAIL);
        boolean valid = email == null || email.isBlank() || (EMAIL.matcher(email).matches()
                && !email.startsWith(".") && !email.contains("..") && !email.contains(".@"));
        return valid ? List.of() : List.of(new FieldError("email", "INVALID_FORMAT"));
    }
}
