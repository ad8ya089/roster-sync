package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.recon.BucketPartitioner;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final RosterProperties properties;
    private final String demoPassword;

    public DemoDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock,
                          RosterProperties properties, @Value("${roster.demo.password}") String demoPassword) {
        if (demoPassword == null || demoPassword.isBlank()) {
            throw new IllegalArgumentException("DEMO_PASSWORD must not be blank");
        }
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.properties = properties;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        UUID acme = seedTenant("00000000-0000-0000-0000-000000000001", "acme", "Acme");
        UUID globex = seedTenant("00000000-0000-0000-0000-000000000002", "globex", "Globex");
        seedOffice(acme, "BLR-1", true);
        seedOffice(acme, "BLR-2", true);
        seedOffice(acme, "BLR-3", false);
        seedOffice(globex, "HYD-1", true);
        String passwordHash = passwordEncoder.encode(demoPassword);
        for (UUID tenantId : new UUID[]{acme, globex}) {
            String code = tenantId.equals(acme) ? "acme" : "globex";
            seedUser(tenantId, code + ".hr", "HR_SYSTEM", null, passwordHash);
            seedUser(tenantId, code + ".admin", "TRANSPORT_ADMIN", null, passwordHash);
            seedUser(tenantId, code + ".operator", "OPERATOR", null, passwordHash);
        }
        seedUser(acme, "E1001", "EMPLOYEE", "E1001", passwordHash);
        Instant now = clock.instant();
        Long officeId = jdbc.queryForObject("SELECT id FROM office WHERE tenant_id=? AND code='BLR-1'", Long.class, acme);
        for (int index = 1; index <= 5; index++) {
            String employeeId = "E100" + index;
            jdbc.update("""
                    INSERT INTO employee (tenant_id, external_id, name, email, phone, office_id,
                      shift_code, status, hr_version, hr_updated_at, recon_bucket, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'S1', 'ACTIVE', 1, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, external_id) DO NOTHING
                    """, acme, employeeId, "Demo Employee " + index, employeeId.toLowerCase(java.util.Locale.ROOT) + "@acme.test",
                    "+91980000100" + index, officeId, Timestamp.from(now),
                    BucketPartitioner.bucketOf(employeeId, properties.reconciliation().buckets()),
                    Timestamp.from(now), Timestamp.from(now));
        }
        seedBookings(acme, now);
        for (int seat = 1; seat <= 4; seat++) {
            jdbc.update("""
                    INSERT INTO route_seat (tenant_id, route_code, seat_no, employee_external_id, updated_at)
                    VALUES (?, 'R-BLR-07', ?, ?, ?) ON CONFLICT (tenant_id, route_code, seat_no) DO NOTHING
                    """, acme, seat, seat == 2 ? "E1002" : null, Timestamp.from(now));
        }
    }

    private UUID seedTenant(String id, String code, String name) {
        jdbc.update("INSERT INTO tenant (id, code, name, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.fromString(id), code, name, Timestamp.from(clock.instant()));
        UUID tenantId = jdbc.queryForObject("SELECT id FROM tenant WHERE code=?", UUID.class, code);
        jdbc.update("INSERT INTO tenant_settings (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING", tenantId);
        return tenantId;
    }

    private void seedOffice(UUID tenantId, String code, boolean active) {
        jdbc.update("""
                INSERT INTO office (tenant_id, code, name, active, created_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) DO NOTHING
                """, tenantId, code, code, active, Timestamp.from(clock.instant()));
    }

    private void seedUser(UUID tenantId, String username, String role, String employeeId, String passwordHash) {
        if (!com.moveinsync.rostersync.domain.model.Role.valueOf(role).hasRequiredEmployeeId(employeeId)) {
            throw new IllegalArgumentException("EMPLOYEE users require employee_external_id");
        }
        jdbc.update("""
                INSERT INTO app_user (tenant_id, username, password_hash, role, employee_external_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (username) DO NOTHING
                """, tenantId, username, passwordHash, role, employeeId, Timestamp.from(clock.instant()));
    }

    private void seedBookings(UUID tenantId, Instant now) {
        // No natural booking key exists in V1. Preserve existing bookings on every restart.
        Long count = jdbc.queryForObject("SELECT count(*) FROM cab_booking WHERE tenant_id=? AND employee_external_id='E1002'",
                Long.class, tenantId);
        if (count != null && count > 0) {
            return;
        }
        for (int days : new int[]{1, 2, -1}) {
            jdbc.update("""
                    INSERT INTO cab_booking (tenant_id, employee_external_id, pickup_at, status, updated_at)
                    VALUES (?, 'E1002', ?, ?, ?)
                    """, tenantId, Timestamp.from(now.plus(Duration.ofDays(days))),
                    days > 0 ? "SCHEDULED" : "COMPLETED", Timestamp.from(now));
        }
    }
}
