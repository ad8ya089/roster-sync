package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.domain.model.Role;
import java.util.UUID;

public record CurrentUser(String username, UUID tenantId, String tenantCode, Role role, String employeeId) {
    public CurrentUser {
        if (username == null || username.isBlank() || tenantId == null || tenantCode == null
                || tenantCode.isBlank() || role == null || !role.hasRequiredEmployeeId(employeeId)) {
            throw new IllegalArgumentException("Invalid authenticated identity");
        }
    }
    @Override public String toString() { return "CurrentUser[tenantId=" + tenantId + ", role=" + role + "]"; }
}
