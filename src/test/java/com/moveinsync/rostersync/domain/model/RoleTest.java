package com.moveinsync.rostersync.domain.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RoleTest {
    @Test void employeeRequiresAnIdWhileOtherRolesDoNot() {
        assertThat(Role.EMPLOYEE.hasRequiredEmployeeId(null)).isFalse();
        assertThat(Role.EMPLOYEE.hasRequiredEmployeeId(" ")).isFalse();
        assertThat(Role.EMPLOYEE.hasRequiredEmployeeId("E".repeat(65))).isFalse();
        assertThat(Role.EMPLOYEE.hasRequiredEmployeeId("E1001")).isTrue();
        for (Role role : Role.values()) {
            if (role != Role.EMPLOYEE) assertThat(role.hasRequiredEmployeeId(null)).isTrue();
        }
    }
}
