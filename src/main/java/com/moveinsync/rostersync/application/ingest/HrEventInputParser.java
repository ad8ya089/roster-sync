package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.domain.command.*;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.validation.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class HrEventInputParser {
    private static final Set<String> METADATA = Set.of("type", "employeeId", "version", "eventId", "occurredAt");
    private final FieldOwnershipPolicy policy;
    private final RecordValidator validator;
    public HrEventInputParser(FieldOwnershipPolicy policy, RecordValidator validator) { this.policy=policy; this.validator=validator; }

    public ParsedEvent parse(UUID tenantId, Object input) {
        var errors = new ArrayList<FieldError>();
        Map<?, ?> raw = input instanceof Map<?, ?> map ? map : Map.of();
        if (!(input instanceof Map)) errors.add(new FieldError("record", "INVALID_FORMAT"));
        String employeeId = string(raw, "employeeId", errors);
        String type = string(raw, "type", errors);
        String eventId = string(raw, "eventId", errors);
        if (eventId != null && eventId.length() > 100) errors.add(new FieldError("eventId", "TOO_LONG"));
        long version = version(raw.get("version"));
        Instant occurredAt = null;
        String occurred = string(raw, "occurredAt", errors);
        if (occurred != null) try { occurredAt = Instant.parse(occurred); } catch (java.time.DateTimeException ignored) {errors.add(new FieldError("occurredAt", "INVALID_FORMAT"));}
        var fields = new EnumMap<EmployeeField, String>(EmployeeField.class);
        var unknown = new HashSet<String>();
        for (var entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (METADATA.contains(key)) continue;
            var field = Arrays.stream(EmployeeField.values()).filter(value -> value.jsonName().equals(key)).findFirst();
            if (field.isEmpty()) unknown.add(key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") ? key : "unknownField");
            else fields.put(field.get(), "DEACTIVATE".equals(type) || policy.ownerOf(field.get()) != FieldOwner.HR
                    ? null : string(raw, key, errors));
        }
        // Email normalises before validation. Phone keeps its exact input through validation.
        if (fields.containsKey(EmployeeField.EMAIL)) fields.put(EmployeeField.EMAIL, EmployeeField.EMAIL.canonicalValue(fields.get(EmployeeField.EMAIL)));
        var checked = validator.validateEvent(tenantId, employeeId, version, type, fields, unknown);
        errors.addAll(checked.errors());
        var validation = new ValidationResult(errors, checked.warnings());
        String safeId = employeeId != null && employeeId.matches("[A-Za-z0-9_-]{1,64}") ? employeeId : null;
        if (!validation.valid()) return new ParsedEvent(null, safeId, version,
                Set.of("CREATE","UPDATE","DEACTIVATE").contains(type == null ? "" : type) ? type : "UNKNOWN", validation);
        var kept = policy.filter(WriteSource.HR_EVENT, fields).kept();
        var normalized = new EnumMap<EmployeeField, String>(EmployeeField.class);
        kept.forEach((field,value) -> normalized.put(field, field == EmployeeField.PHONE ? value : field.canonicalValue(value)));
        var snapshot = new HrSnapshot(normalized.get(EmployeeField.NAME), normalized.get(EmployeeField.EMAIL), normalized.get(EmployeeField.PHONE),
                normalized.get(EmployeeField.MANAGER_ID), normalized.get(EmployeeField.OFFICE_CODE), normalized.get(EmployeeField.SHIFT_CODE),
                normalized.get(EmployeeField.STATUS), null, null);
        HrCommand command = switch (HrEventType.valueOf(type)) {
            case CREATE -> new CreateEmployee(employeeId, version, eventId, occurredAt, snapshot);
            case UPDATE -> new UpdateEmployee(employeeId, version, eventId, occurredAt, snapshot);
            case DEACTIVATE -> new DeactivateEmployee(employeeId, version, eventId, occurredAt);
        };
        return new ParsedEvent(command, safeId, version, type, validation);
    }
    private static String string(Map<?, ?> raw, String key, List<FieldError> errors) {
        Object value = raw.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text;
        errors.add(new FieldError(key, "INVALID_FORMAT")); return null;
    }
    private static long version(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) return ((Number)value).longValue();
        if (value instanceof BigInteger integer && integer.bitLength() <= 63) return integer.longValue();
        return 0;
    }
    public record ParsedEvent(HrCommand command, String employeeId, long version, String type, ValidationResult validation) { }
}
