package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.model.Role;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_user")
@Access(AccessType.FIELD)
public class AppUserEntity extends TenantScopedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100) private String username;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role;
    @Column(name = "employee_external_id", length = 64) private String employeeExternalId;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AppUserEntity() { }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getEmployeeExternalId() { return employeeExternalId; }
    public boolean isEnabled() { return enabled; }
}
