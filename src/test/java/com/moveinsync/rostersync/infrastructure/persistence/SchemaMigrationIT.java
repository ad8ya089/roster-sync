package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.AbstractIntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationIT extends AbstractIntegrationTest {
    @Autowired
    private Flyway flyway;

    @Test
    void appliesV1AndCreatesEveryCoreTable() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        var tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class);
        assertThat(tables).contains("tenant", "tenant_settings", "office", "app_user", "employee",
                "hr_event_receipt", "record_rejection", "outbox_event", "recon_run", "recon_action",
                "ops_alert", "cab_booking", "route_seat", "processed_event");
    }

    @Test
    void storesHealCounterAndExcludesDryRunsFromTheActiveIndex() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname='ux_recon_one_active'", String.class);
        assertThat(definition).contains("UPLOADED", "DIFFING", "AWAITING_APPROVAL", "APPLYING")
                .doesNotContain("PLANNED");
        UUID tenantId = UUID.randomUUID();
        insertRun(tenantId, "PLANNED");
        insertRun(tenantId, "PLANNED");
        insertRun(tenantId, "UPLOADED");
        assertThat(jdbc.queryForObject("SELECT sum(planned_heals) FROM recon_run", Long.class)).isZero();
        assertThatThrownBy(() -> insertRun(tenantId, "DIFFING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsActiveEmployeeWithoutRequiredHrStateButAllowsATombstone() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name) VALUES (?, 'schema-test', 'Schema test')", tenantId);
        String insert = """
                INSERT INTO employee (tenant_id, external_id, status, hr_version, hr_updated_at, recon_bucket)
                VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, 0)
                """;
        assertThatThrownBy(() -> jdbc.update(insert, tenantId, "E1", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, tenantId, "E1", "INACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id=?", Long.class, tenantId))
                .isEqualTo(1);
    }

    private void insertRun(UUID tenantId, String status) {
        jdbc.update("""
                INSERT INTO recon_run (id, tenant_id, status, file_path, as_of, created_by)
                VALUES (?, ?, ?, '/data/test.csv', CURRENT_TIMESTAMP, 'schema-test')
                """, UUID.randomUUID(), tenantId, status);
    }
}
