package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.rules.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RecordValidator {
    private final FieldOwnershipPolicy ownership;
    private final List<ValidationRule<ValidationInput>> rules;

    public RecordValidator(FieldOwnershipPolicy ownership, OfficeLookup offices) {
        this.ownership = ownership;
        rules = List.of(new RequiredFieldsRule(ownership), new IdentityAndStatusRule(), new PhoneRule(),
                new EmailRule(), new LengthRule(), new OfficeRule(offices));
    }

    public ValidationResult validateEvent(UUID tenantId, String employeeId, long version, String eventType,
                                          Map<EmployeeField, String> fields, Set<String> unknownFields) {
        return validate(tenantId, employeeId, version, eventType, WriteSource.HR_EVENT, fields, unknownFields);
    }

    public ValidationResult validateFileRow(UUID tenantId, String employeeId, long version,
                                            Map<EmployeeField, String> fields, Set<String> unknownFields) {
        return validate(tenantId, employeeId, version, "UPDATE", WriteSource.HR_SNAPSHOT, fields, unknownFields);
    }

    public ValidationResult validateTransportFields(UUID tenantId, String employeeId, Map<EmployeeField,String> fields) {
        var input = new ValidationInput(tenantId, employeeId, 1, "UPDATE", WriteSource.TRANSPORT_ADMIN, fields);
        // A PATCH validates supplied values; full-HR required fields do not apply to an absent key.
        return new ValidationResult(rules.stream().skip(1).flatMap(rule -> rule.validate(input).stream()).toList(), List.of());
    }

    private ValidationResult validate(UUID tenantId, String employeeId, long version, String eventType, WriteSource source,
                                      Map<EmployeeField, String> fields, Set<String> unknownFields) {
        var warnings = new ArrayList<FieldError>();
        unknownFields.forEach(field -> warnings.add(new FieldError(field, "UNKNOWN_FIELD_IGNORED")));
        var kept = new EnumMap<EmployeeField, String>(EmployeeField.class);
        if ("DEACTIVATE".equals(eventType)) {
            fields.keySet().forEach(field -> warnings.add(new FieldError(field.jsonName(), "UNKNOWN_FIELD_IGNORED")));
        } else {
            var filtered = ownership.filter(source, fields);
            kept.putAll(filtered.kept());
            filtered.ignoredNotOwned().forEach(field -> warnings.add(new FieldError(field.jsonName(), "FIELD_NOT_OWNED_BY_SOURCE")));
            if ("CREATE".equals(eventType) && kept.get(EmployeeField.STATUS) == null && ownership.ownerOf(EmployeeField.STATUS) == FieldOwner.HR) {
                kept.put(EmployeeField.STATUS, "ACTIVE");
            }
        }
        var input = new ValidationInput(tenantId, employeeId, version, eventType, source, kept);
        var errors = rules.stream().flatMap(rule -> rule.validate(input).stream()).toList();
        return new ValidationResult(errors, warnings);
    }
}
