package com.moveinsync.rostersync.application.ingest;
import com.moveinsync.rostersync.domain.validation.FieldError;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RejectionWriter {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics;
    public RejectionWriter(JdbcClient jdbc, Clock clock,com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics) { this.jdbc = jdbc; this.clock = clock;this.metrics=metrics; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UUID tenantId, String employeeId, Long version, List<FieldError> errors) {
        String safeId = employeeId != null && employeeId.matches("[A-Za-z0-9_-]{1,64}") ? employeeId : null;
        // Store only known field identifiers/codes, never the failed input or parser/driver messages.
        String codes = errors.stream().map(error -> error.field() + ":" + error.code()).distinct().sorted().collect(Collectors.joining(","));
        if (codes.length() > 500) codes = "record:VALIDATION_FAILED";
        jdbc.sql("INSERT INTO record_rejection(tenant_id,source,external_id,version,error_codes,created_at) VALUES(?,'API',?,?,?,?)")
                .params(Arrays.asList(tenantId, safeId, version, codes, Timestamp.from(clock.instant()))).update();
        errors.stream().map(FieldError::code).distinct().forEach(code->com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics.committed(()->metrics.rejection(code,"api")));
    }
}
