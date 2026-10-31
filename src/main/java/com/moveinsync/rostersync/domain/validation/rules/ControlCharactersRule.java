package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.ArrayList;
import java.util.List;

/** Reject before trimming: PostgreSQL text cannot represent NUL; no C0 is valid roster data. */
public final class ControlCharactersRule implements ValidationRule<ValidationInput> {
    public static boolean containsControl(String value) {
        return value!=null && value.chars().anyMatch(character->character<32);
    }
    @Override public List<FieldError> validate(ValidationInput input) {
        var errors=new ArrayList<FieldError>();
        for(var field:EmployeeField.values()) if(containsControl(input.get(field)))
            errors.add(new FieldError(field.jsonName(),"INVALID_FORMAT"));
        return errors;
    }
}
