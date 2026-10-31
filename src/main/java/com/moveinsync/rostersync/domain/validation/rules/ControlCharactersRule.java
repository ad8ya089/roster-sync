package com.moveinsync.rostersync.domain.validation.rules;

import com.moveinsync.rostersync.domain.model.EmployeeField;
import com.moveinsync.rostersync.domain.validation.*;
import java.util.ArrayList;
import java.util.List;

/** Reject before trimming: C0/DEL are invalid roster data, including hash separators/null markers. */
public final class ControlCharactersRule implements ValidationRule<ValidationInput> {
    public static boolean containsControl(String value) {
        return value!=null && value.chars().anyMatch(character->character<32 || character==127);
    }
    @Override public List<FieldError> validate(ValidationInput input) {
        var errors=new ArrayList<FieldError>();
        for(var field:EmployeeField.values()) if(containsControl(input.get(field)))
            errors.add(new FieldError(field.jsonName(),"INVALID_FORMAT"));
        return errors;
    }
}
