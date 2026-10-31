package com.moveinsync.rostersync.domain.validation;

import java.util.Comparator;
import java.util.List;

public record ValidationResult(List<FieldError> errors, List<FieldError> warnings, int warningCount) {
    public ValidationResult(List<FieldError> errors,List<FieldError> warnings) {this(errors,warnings,warnings.size());}
    public ValidationResult {
        errors = ordered(errors);
        warnings = ordered(warnings.stream().map(warning -> new FieldError(
                "UNKNOWN_FIELD_IGNORED".equals(warning.code()) && !warning.field().matches("[A-Za-z][A-Za-z0-9_]{0,39}")
                        ? "(unrecognised)" : warning.field(),warning.code())).toList()).stream().limit(10).toList();
    }
    private static List<FieldError> ordered(List<FieldError> values) {
        return values.stream().distinct().sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::code)).toList();
    }
    public boolean valid() { return errors.isEmpty(); }
}
