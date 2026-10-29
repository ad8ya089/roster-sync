package com.moveinsync.rostersync.domain.validation;

import java.util.Comparator;
import java.util.List;

public record ValidationResult(List<FieldError> errors, List<FieldError> warnings) {
    public ValidationResult {
        errors = ordered(errors);
        warnings = ordered(warnings);
    }
    private static List<FieldError> ordered(List<FieldError> values) {
        return values.stream().distinct().sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::code)).toList();
    }
    public boolean valid() { return errors.isEmpty(); }
}
