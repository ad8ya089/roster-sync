package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class RecordValidatorTest {
    @Test void controlCharactersInOfficeCodesAreRejectedBeforeAnyDatabaseLookup() {
        var lookup=org.mockito.Mockito.mock(OfficeLookup.class);
        var checked=new RecordValidator(FieldOwnershipPolicy.defaults(),lookup);
        var fields=validFields();fields.put(EmployeeField.OFFICE_CODE,"BLR\u0000-1");
        assertThat(checked.validateEvent(TENANT,"E1",1,"UPDATE",fields,Set.of()).errors()).contains(new FieldError("officeCode","INVALID_FORMAT"));
        org.mockito.Mockito.verifyNoInteractions(lookup);
    }
    @Test void rejectsEveryC0ControlBeforeNormalisationForEventsAndFiles() {
        var allHr=new RecordValidator(new FieldOwnershipPolicy(Set.of()),offices);
        for(var field:EmployeeField.values()) for(int code=0;code<32;code++) {
            var fields=validFields();fields.put(field,"A"+(char)code+"B");
            assertThat(allHr.validateEvent(TENANT,"E1",1,"UPDATE",fields,Set.of()).errors())
                    .as("event %s control %s",field,code).contains(new FieldError(field.jsonName(),"INVALID_FORMAT"));
            assertThat(allHr.validateFileRow(TENANT,"E1",1,fields,Set.of()).errors())
                    .as("file %s control %s",field,code).contains(new FieldError(field.jsonName(),"INVALID_FORMAT"));
        }
        var fields=validFields();fields.put(EmployeeField.EMAIL,"\u0000test@example.test\u0000");
        assertThat(event("UPDATE",fields).errors()).contains(new FieldError("email","INVALID_FORMAT"));
    }
    @org.junit.jupiter.api.Test
    void transportPatchValidatesOnlySuppliedValuesAndDoesNotRequireHrSnapshotFields() {
        var validator = new RecordValidator(com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy.defaults(), (tenant,code) -> OfficeLookup.Status.ACTIVE);
        var clear = new java.util.EnumMap<EmployeeField,String>(EmployeeField.class);
        clear.put(EmployeeField.PICKUP_POINT,null);
        assertThat(validator.validateTransportFields(java.util.UUID.randomUUID(),"E1",clear).valid()).isTrue();
        assertThat(validator.validateTransportFields(java.util.UUID.randomUUID(),"E1",java.util.Map.of(EmployeeField.PHONE,"+91 9800000001")).errors())
                .contains(new com.moveinsync.rostersync.domain.validation.FieldError("phone","INVALID_FORMAT"));
    }
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final OfficeLookup offices = (tenant, code) -> {
        assertThat(tenant).isEqualTo(TENANT);
        return switch (code) { case "BLR-1" -> OfficeLookup.Status.ACTIVE; case "BLR-3" -> OfficeLookup.Status.INACTIVE; default -> OfficeLookup.Status.UNKNOWN; };
    };
    private final RecordValidator validator = new RecordValidator(FieldOwnershipPolicy.defaults(), offices);

    @Test
    void createRequiresFullStateAndDefaultsStatusButUpdateRequiresIt() {
        var fields = validFields();
        fields.remove(EmployeeField.STATUS);
        assertThat(event("CREATE", fields).valid()).isTrue();
        assertThat(event("UPDATE", fields).errors()).containsExactly(new FieldError("status", "MISSING_REQUIRED_FIELD"));
        assertThat(event("CREATE", Map.of()).errors()).containsExactly(new FieldError("email", "MISSING_REQUIRED_FIELD"),
                new FieldError("name", "MISSING_REQUIRED_FIELD"), new FieldError("officeCode", "MISSING_REQUIRED_FIELD"));
        fields.put(EmployeeField.STATUS, "INACTIVE");
        fields.remove(EmployeeField.NAME);
        assertThat(event("UPDATE", fields).errors()).contains(new FieldError("name", "MISSING_REQUIRED_FIELD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+919800000001", "+12345678", "+123456789012345"})
    void acceptsExactE164(String phone) {
        var fields = validFields(); fields.put(EmployeeField.PHONE, phone);
        assertThat(event("UPDATE", fields).valid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {" +919800000001", "+919800000001 ", "+91-9800000001", "+91 9800000001", "919800000001", "+01234567", "+1234567", "+1234567890123456", ""})
    void rejectsPhoneWithoutNormalizingIt(String phone) {
        var fields = validFields(); fields.put(EmployeeField.PHONE, phone);
        assertThat(event("UPDATE", fields).errors()).contains(new FieldError("phone", "INVALID_FORMAT"));
    }

    @Test
    void emailIsTrimmedAndLowercasedBeforeValidation() {
        var fields = validFields(); fields.put(EmployeeField.EMAIL, "  PERSON@EXAMPLE.TEST  ");
        assertThat(event("UPDATE", fields).valid()).isTrue();
        var input = new ValidationInput(TENANT, "E1", 1, "UPDATE", WriteSource.HR_EVENT, fields);
        assertThat(input.get(EmployeeField.EMAIL)).isEqualTo("person@example.test");
        fields.clear();
        assertThat(input.fields()).isNotEmpty();
        assertThat(input.toString()).doesNotContain("person@example.test", "Test Person");
        assertThatThrownBy(() -> input.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"person", "person@", "@example.test", "person@example", ".person@example.test", "per..son@example.test", "person.@example.test", "per son@example.test"})
    void rejectsMalformedEmail(String email) {
        var fields = validFields(); fields.put(EmployeeField.EMAIL, email);
        assertThat(event("UPDATE", fields).errors()).contains(new FieldError("email", "INVALID_FORMAT"));
    }

    @Test
    void returnsAllErrorsSortedWithoutRejectedValues() {
        var fields = validFields();
        fields.put(EmployeeField.EMAIL, "bad email"); fields.put(EmployeeField.PHONE, "bad phone");
        fields.put(EmployeeField.OFFICE_CODE, "UNKNOWN"); fields.put(EmployeeField.MANAGER_ID, "E1");
        fields.put(EmployeeField.SHIFT_CODE, "lowercase"); fields.put(EmployeeField.STATUS, "WRONG");
        var result = validator.validateEvent(TENANT, "E1", 0, "UPDATE", fields, Set.of());
        assertThat(result.errors()).containsExactly(new FieldError("email", "INVALID_FORMAT"), new FieldError("managerId", "MANAGER_SELF_REFERENCE"),
                new FieldError("officeCode", "UNKNOWN_OFFICE"), new FieldError("phone", "INVALID_FORMAT"), new FieldError("shiftCode", "INVALID_FORMAT"),
                new FieldError("status", "INVALID_STATUS"), new FieldError("version", "INVALID_VERSION"));
        assertThat(result.toString()).doesNotContain("bad email", "bad phone");
        assertThatThrownBy(() -> result.errors().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesIdentifiersOfficeActivityAndLengths() {
        var fields = validFields(); fields.put(EmployeeField.OFFICE_CODE, "BLR-3"); fields.put(EmployeeField.MANAGER_ID, "bad manager");
        assertThat(validator.validateEvent(TENANT, "bad id", -1, "WRONG", fields, Set.of()).errors()).contains(
                new FieldError("employeeId", "INVALID_FORMAT"), new FieldError("managerId", "INVALID_FORMAT"),
                new FieldError("officeCode", "INACTIVE_OFFICE"), new FieldError("type", "INVALID_EVENT_TYPE"), new FieldError("version", "INVALID_VERSION"));
        assertThat(validator.validateEvent(TENANT, null, 1, null, Map.of(), Set.of()).errors()).contains(new FieldError("employeeId", "MISSING_REQUIRED_FIELD"));
        assertThat(validator.validateEvent(TENANT, " ", 1, "CREATE", Map.of(), Set.of()).errors()).contains(new FieldError("employeeId", "MISSING_REQUIRED_FIELD"));
        var allHr = new RecordValidator(new FieldOwnershipPolicy(Set.of()), offices);
        for (EmployeeField field : EmployeeField.values()) {
            fields = validFields(); fields.put(field, "x".repeat(field.maximumLength() + 1));
            assertThat(allHr.validateEvent(TENANT, "E1", 1, "UPDATE", fields, Set.of()).errors()).contains(new FieldError(field.jsonName(), "TOO_LONG"));
        }
        assertThat(validator.validateEvent(TENANT, "E".repeat(65), 1, "DEACTIVATE", Map.of(), Set.of()).errors()).contains(new FieldError("employeeId", "TOO_LONG"));
    }

    @Test
    void deactivateIgnoresAllCarriedFieldsWithWarningsAndNoPayloadValidation() {
        var fields = validFields(); fields.put(EmployeeField.PHONE, "invalid"); fields.put(EmployeeField.PICKUP_POINT, "Ignored");
        var result = validator.validateEvent(TENANT, "E1", 2, "DEACTIVATE", fields, Set.of("unknown"));
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).hasSize(fields.size() + 1).allSatisfy(warning -> assertThat(warning.code()).isEqualTo("UNKNOWN_FIELD_IGNORED"));
        assertThat(validator.validateEvent(TENANT, "E1", 2, "DEACTIVATE", Map.of(), Set.of()).valid()).isTrue();
    }

    @Test
    void ownershipWarningsDropValuesBeforeValidationAndMovingPhoneTakesEffect() {
        var fields = validFields(); fields.put(EmployeeField.PICKUP_POINT, "x".repeat(301));
        var result = validator.validateEvent(TENANT, "E1", 1, "UPDATE", fields, Set.of("unknown"));
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).containsExactly(new FieldError("pickupPoint", "FIELD_NOT_OWNED_BY_SOURCE"), new FieldError("unknown", "UNKNOWN_FIELD_IGNORED"));
        var moved = new RecordValidator(new FieldOwnershipPolicy(Set.of(EmployeeField.PHONE)), offices);
        fields.put(EmployeeField.PHONE, "not E164"); fields.remove(EmployeeField.PICKUP_POINT);
        assertThat(moved.validateEvent(TENANT, "E1", 1, "UPDATE", fields, Set.of()).valid()).isTrue();
    }

    @Test
    void fileRowsRequireStatusButAllowInactiveTombstonesAndReturnWarnings() {
        assertThat(validator.validateFileRow(TENANT, "E1", 1, validFields(), Set.of("unknown")).valid()).isTrue();
        assertThat(validator.validateFileRow(TENANT, "E1", 1, Map.of(EmployeeField.STATUS, "INACTIVE"), Set.of()).valid()).isTrue();
        assertThat(validator.validateFileRow(TENANT, "E1", 1, Map.of(), Set.of()).errors()).contains(new FieldError("status", "MISSING_REQUIRED_FIELD"));
        var fields = validFields(); fields.put(EmployeeField.STATUS, "");
        assertThat(event("CREATE", fields).errors()).contains(new FieldError("status", "INVALID_STATUS"));
        assertThat(event("UPDATE", fields).errors()).contains(new FieldError("status", "MISSING_REQUIRED_FIELD"));
        fields.remove(EmployeeField.PHONE); fields.remove(EmployeeField.SHIFT_CODE); fields.remove(EmployeeField.MANAGER_ID);
        fields.put(EmployeeField.STATUS, "ACTIVE");
        assertThat(event("UPDATE", fields).valid()).isTrue();
        var ordered = new ValidationResult(java.util.List.of(new FieldError("z", "B"), new FieldError("z", "A"), new FieldError("z", "A")), java.util.List.of());
        assertThat(ordered.errors()).containsExactly(new FieldError("z", "A"), new FieldError("z", "B"));
    }

    private ValidationResult event(String type, Map<EmployeeField, String> fields) {
        return validator.validateEvent(TENANT, "E1", 1, type, fields, Set.of());
    }
    private EnumMap<EmployeeField, String> validFields() {
        var fields = new EnumMap<EmployeeField, String>(EmployeeField.class);
        fields.put(EmployeeField.NAME, "Test Person"); fields.put(EmployeeField.EMAIL, "test@example.test");
        fields.put(EmployeeField.PHONE, "+919800000001"); fields.put(EmployeeField.OFFICE_CODE, "BLR-1");
        fields.put(EmployeeField.MANAGER_ID, "M1"); fields.put(EmployeeField.SHIFT_CODE, "S1"); fields.put(EmployeeField.STATUS, "ACTIVE");
        return fields;
    }
}
