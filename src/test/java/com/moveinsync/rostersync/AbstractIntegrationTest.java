package com.moveinsync.rostersync;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUrlParam("reWriteBatchedInserts","true");

    static {
        // One container for the entire test JVM; per-class extension shutdown breaks cached contexts.
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        truncateBusinessTables();
    }

    protected final void truncateBusinessTables() {
        jdbc.execute("""
                TRUNCATE TABLE processed_event, route_seat, cab_booking, ops_alert, recon_action, recon_run,
                  outbox_event, record_rejection, hr_event_receipt, employee,
                  app_user, office, tenant_settings, tenant RESTART IDENTITY CASCADE
                """);
    }
}
