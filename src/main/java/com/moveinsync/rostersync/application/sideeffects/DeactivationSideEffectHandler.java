package com.moveinsync.rostersync.application.sideeffects;

import com.moveinsync.rostersync.application.outbox.*;
import com.moveinsync.rostersync.infrastructure.persistence.EmployeeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Order(0)
public class DeactivationSideEffectHandler implements EventSink {
    private final JdbcClient jdbc;
    private final EmployeeRepository employees;
    private final Clock clock;
    private final MeterRegistry metrics;
    public DeactivationSideEffectHandler(JdbcClient jdbc,EmployeeRepository employees,Clock clock,MeterRegistry metrics) {
        this.jdbc=jdbc; this.employees=employees; this.clock=clock; this.metrics=metrics;
    }
    public String name() { return "deactivation"; }
    public boolean supports(String type) { return "EmployeeDeactivated".equals(type); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void handle(OutboxEventView event) {
        // Independent consumer commit survives a publisher crash before PUBLISHED is recorded.
        int inserted=jdbc.sql("INSERT INTO processed_event(sink,event_id,processed_at) VALUES (?,?,?) ON CONFLICT DO NOTHING")
                .params(name(),event.eventId(),Timestamp.from(clock.instant())).update();
        if(inserted==0) return;
        var employee=employees.findForUpdate(event.tenantId(),event.aggregateId());
        if(employee.isEmpty()) return;
        if(!"INACTIVE".equals(employee.get().snapshot().status())) {
            metrics.counter("sideeffects.skipped","reason","reactivated").increment(); return;
        }
        // FOR UPDATE closes the rehire race: reactivation and booking's FOR SHARE cannot
        // interleave between this status check and cancellation. Recon need not bump a version.
        var now=Timestamp.from(clock.instant());
        int cancelled=jdbc.sql("""
                UPDATE cab_booking SET status='CANCELLED',cancel_reason='EMPLOYEE_DEACTIVATED',updated_at=?
                WHERE tenant_id=? AND employee_external_id=? AND status='SCHEDULED' AND pickup_at>?
                """).params(now,event.tenantId(),event.aggregateId(),now).update();
        jdbc.sql("UPDATE route_seat SET employee_external_id=NULL,updated_at=? WHERE tenant_id=? AND employee_external_id=?")
                .params(now,event.tenantId(),event.aggregateId()).update();
        metrics.counter("sideeffects.bookings.cancelled").increment(cancelled);
    }
}
