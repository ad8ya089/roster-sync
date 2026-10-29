package com.moveinsync.rostersync.domain.ownership;

import com.moveinsync.rostersync.domain.model.*;
import java.util.EnumMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FieldOwnershipPolicyTest {
    @Test
    void defaultsKeepHrFieldsInDeclarationOrderAndIgnoreTransportFields() {
        var policy = FieldOwnershipPolicy.defaults();
        assertThat(policy.hrFields()).containsExactly(EmployeeField.NAME, EmployeeField.EMAIL, EmployeeField.PHONE,
                EmployeeField.MANAGER_ID, EmployeeField.OFFICE_CODE, EmployeeField.SHIFT_CODE, EmployeeField.STATUS);
        var values = new EnumMap<EmployeeField, String>(EmployeeField.class);
        values.put(EmployeeField.EMAIL, "canary@example.test");
        values.put(EmployeeField.PICKUP_POINT, "Canary Lane");
        values.put(EmployeeField.SPECIAL_ASSISTANCE, null);
        var result = policy.filter(WriteSource.HR_EVENT, values);
        assertThat(result.kept()).containsOnlyKeys(EmployeeField.EMAIL);
        assertThat(result.ignoredNotOwned()).containsExactly(EmployeeField.PICKUP_POINT, EmployeeField.SPECIAL_ASSISTANCE);
        assertThat(result.toString()).doesNotContain("canary@example.test", "Canary Lane");
        values.clear();
        assertThat(result.kept()).hasSize(1);
        assertThatThrownBy(() -> result.kept().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.ignoredNotOwned().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void movingPhoneToTransportFlipsBothWriteDirections() {
        var policy = new FieldOwnershipPolicy(Set.of(EmployeeField.PHONE));
        var input = new EnumMap<EmployeeField, String>(EmployeeField.class);
        input.put(EmployeeField.PHONE, null);
        input.put(EmployeeField.EMAIL, "test@example.test");
        assertThat(policy.ownerOf(EmployeeField.PHONE)).isEqualTo(FieldOwner.TRANSPORT);
        assertThat(policy.filter(WriteSource.HR_SNAPSHOT, input).ignoredNotOwned()).containsExactly(EmployeeField.PHONE);
        var transport = policy.filter(WriteSource.TRANSPORT_ADMIN, input);
        assertThat(transport.kept()).containsEntry(EmployeeField.PHONE, null).hasSize(1);
        assertThat(transport.ignoredNotOwned()).containsExactly(EmployeeField.EMAIL);
    }

    @Test
    void emptyTransportSetMakesEveryFieldHrOwned() {
        var policy = new FieldOwnershipPolicy(Set.of());
        assertThat(policy.hrFields()).containsExactly(EmployeeField.values());
        assertThatNullPointerException().isThrownBy(() -> policy.ownerOf(null));
    }
}
