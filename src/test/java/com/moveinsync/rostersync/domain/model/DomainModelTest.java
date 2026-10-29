package com.moveinsync.rostersync.domain.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DomainModelTest {
    @Test
    void fieldsHaveStableDeclarationOrderAndWireNames() {
        assertThat(EmployeeField.values()).extracting(EmployeeField::jsonName)
                .containsExactly("name", "email", "phone", "managerId", "officeCode", "shiftCode", "status", "pickupPoint", "specialAssistance");
        assertThat(EmployeeField.values()).extracting(EmployeeField::csvName)
                .containsExactly("name", "email", "phone", "manager_id", "office_code", "shift_code", "status", "pickup_point", "special_assistance");
        for (EmployeeField field : EmployeeField.values()) {
            assertThat(field.maximumLength()).isPositive();
            assertThat(field.canonicalValue(null)).isNull();
            assertThat(field.canonicalValue("  VALUE  ")).isEqualTo(field == EmployeeField.EMAIL ? "value" : "VALUE");
        }
        assertThat(WriteSource.HR_EVENT.owner()).isEqualTo(FieldOwner.HR);
        assertThat(WriteSource.HR_SNAPSHOT.owner()).isEqualTo(FieldOwner.HR);
        assertThat(WriteSource.TRANSPORT_ADMIN.owner()).isEqualTo(FieldOwner.TRANSPORT);
    }

    @Test
    void snapshotNormalizesEmailButKeepsPhoneAndNeverPrintsPii() {
        HrSnapshot snapshot = new HrSnapshot("Canary Name", "  CANARY@EXAMPLE.TEST  ", " +919800000001 ",
                "M1", "BLR-1", "S1", "ACTIVE", "Canary Lane", "Canary assistance");
        assertThat(snapshot.email()).isEqualTo("canary@example.test");
        assertThat(snapshot.phone()).isEqualTo(" +919800000001 ");
        for (EmployeeField field : EmployeeField.values()) assertThat(snapshot.get(field)).isNotNull();
        assertThat(snapshot.toString()).isEqualTo("HrSnapshot[managerId=M1, officeCode=BLR-1, shiftCode=S1, status=ACTIVE]");
        assertThat(snapshot.withStatus("INACTIVE").status()).isEqualTo("INACTIVE");
        assertThat(HrSnapshot.tombstone().name()).isNull();
        assertThat(HrSnapshot.tombstone().status()).isEqualTo("INACTIVE");
    }

    @Test
    void transportFieldsDefensivelyCopyNullableValuesAndPrintOnlyKeys() {
        var input = new EnumMap<EmployeeField, String>(EmployeeField.class);
        input.put(EmployeeField.PICKUP_POINT, "Canary Lane");
        input.put(EmployeeField.PHONE, null);
        TransportFields fields = new TransportFields(input);
        input.clear();
        assertThat(fields.values()).hasSize(2).containsEntry(EmployeeField.PHONE, null);
        assertThatThrownBy(() -> fields.values().put(EmployeeField.EMAIL, "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(fields.toString()).doesNotContain("Canary Lane").contains("PICKUP_POINT", "PHONE");
    }

    @Test
    void currentStateDistinguishesMissingFromStoredTombstone() {
        assertThat(CurrentEmployeeState.missing().exists()).isFalse();
        var tombstone = new CurrentEmployeeState(true, 5, EmploymentStatus.INACTIVE, HrSnapshot.tombstone(), Instant.EPOCH);
        assertThat(tombstone.exists()).isTrue();
        assertThat(tombstone.hrVersion()).isEqualTo(5);
        assertThatIllegalArgumentException().isThrownBy(() -> new CurrentEmployeeState(false, 1, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new CurrentEmployeeState(true, 0, EmploymentStatus.INACTIVE, HrSnapshot.tombstone(), Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new CurrentEmployeeState(true, 1, EmploymentStatus.ACTIVE, HrSnapshot.tombstone(), Instant.EPOCH));
        assertThatNullPointerException().isThrownBy(() -> new CurrentEmployeeState(true, 1, null, null, null));
    }
}
