package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.AbstractIntegrationTest;
import com.moveinsync.rostersync.domain.recon.BucketPartitioner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("demo")
@TestPropertySource(properties = "roster.demo.password=test-demo-password")
class DemoDataSeederIT extends AbstractIntegrationTest {
    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void seedsTenantFixturesCredentialsAndCrc32Buckets() {
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM office WHERE tenant_id=?", Long.class, ACME)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT active FROM office WHERE tenant_id=? AND code='BLR-3'", Boolean.class, ACME)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class)).isEqualTo(7);
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE tenant_id=? AND username='acme.hr'", String.class, ACME);
        assertThat(passwordEncoder.matches("test-demo-password", hash)).isTrue();
        var employees = jdbc.queryForList("SELECT external_id, recon_bucket FROM employee WHERE tenant_id=?", ACME);
        assertThat(employees).hasSize(5);
        employees.forEach(row -> assertThat(((Number) row.get("recon_bucket")).intValue())
                .isEqualTo(BucketPartitioner.bucketOf((String) row.get("external_id"), 256)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cab_booking WHERE tenant_id=? AND status='SCHEDULED'", Long.class, ACME)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cab_booking WHERE tenant_id=? AND status='COMPLETED'", Long.class, ACME)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM route_seat WHERE tenant_id=?", Long.class, ACME)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT employee_external_id FROM route_seat WHERE tenant_id=? AND route_code='R-BLR-07' AND seat_no=2",
                String.class, ACME)).isEqualTo("E1002");
    }

    @Test
    void rerunPreservesChangesAndDoesNotDuplicateBookingsOrSeats() {
        seeder.run(new DefaultApplicationArguments());
        jdbc.update("UPDATE employee SET shift_code='NIGHT' WHERE tenant_id=? AND external_id='E1002'", ACME);
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT shift_code FROM employee WHERE tenant_id=? AND external_id='E1002'", String.class, ACME)).isEqualTo("NIGHT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id=?", Long.class, ACME)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cab_booking WHERE tenant_id=?", Long.class, ACME)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM route_seat WHERE tenant_id=?", Long.class, ACME)).isEqualTo(4);
    }
}
