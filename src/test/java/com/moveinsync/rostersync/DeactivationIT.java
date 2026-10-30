package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.sideeffects.*;
import com.moveinsync.rostersync.application.outbox.OutboxEventView;
import com.moveinsync.rostersync.domain.model.Role;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class DeactivationIT extends AbstractOutboxIntegrationTest {
    @Autowired DeactivationSideEffectHandler effects;
    @Autowired BookingService bookings;
    @Test void onlyFutureScheduledBookingsAreCancelledAndSeatsAreFreedOnce() throws Exception {
        jdbc.update("UPDATE cab_booking SET status='SCHEDULED' WHERE tenant_id=? AND pickup_at<?",JwtTestTokens.ACME,java.sql.Timestamp.from(clock.instant()));
        send(event("E1002",2,"DEACTIVATE",null,null));publisher.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cab_booking WHERE status='CANCELLED' AND cancel_reason='EMPLOYEE_DEACTIVATED'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM cab_booking WHERE pickup_at<?",String.class,java.sql.Timestamp.from(clock.instant()))).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT employee_external_id FROM route_seat WHERE seat_no=2",String.class)).isNull();
        var row=jdbc.queryForMap("SELECT id,event_id FROM outbox_event WHERE event_type='EmployeeDeactivated'");
        var event=new OutboxEventView(((Number)row.get("id")).longValue(),(UUID)row.get("event_id"),JwtTestTokens.ACME,"E1002",2L,"EmployeeDeactivated","{}",0);
        var before=jdbc.queryForObject("SELECT json_agg(cab_booking ORDER BY id)::text FROM cab_booking",String.class);
        effects.handle(event);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT json_agg(cab_booking ORDER BY id)::text FROM cab_booking",String.class)).isEqualTo(before);
        assertThatThrownBy(()->bookings.create(JwtTestTokens.ACME,"E1002",clock.instant().plusSeconds(3600))).hasMessage("Employee inactive");
    }
    @Test void delayedDeactivationAfterRehireLeavesNewBookingsIntact() throws Exception {
        var failOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
        sink.action(event->{if(event.eventType().equals("EmployeeDeactivated") && failOnce.getAndSet(false)) throw new IllegalStateException();});
        send(event("E1002",2,"DEACTIVATE",null,null));
        publisher.tick();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE event_type='EmployeeDeactivated'",Integer.class)).isEqualTo(1);
        send(event("E1002",3,"UPDATE","BLR-1","S1"));publisher.tick();
        var booking=bookings.create(JwtTestTokens.ACME,"E1002",clock.instant().plusSeconds(7200));
        clock.advance(Duration.ofSeconds(1));publisher.tick();
        assertThat(jdbc.queryForObject("SELECT status FROM cab_booking WHERE tenant_id=? AND id=?",String.class,JwtTestTokens.ACME,booking.id())).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cab_booking WHERE status='CANCELLED'",Long.class)).isZero();
        assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void bookingReadsAreSelfScopedAndCrossTenantIdsAreHidden() throws Exception {
        assertThat(request("GET","/api/v1/employees/E1002/bookings",token("globex.admin"),null).statusCode()).isEqualTo(404);
        assertThat(request("GET","/api/v1/employees/E1002/bookings",jwt(Role.EMPLOYEE),null).statusCode()).isEqualTo(404);
        assertThat(request("GET","/api/v1/employees/E1001/bookings",jwt(Role.EMPLOYEE),null).statusCode()).isEqualTo(200);
        assertThat(request("GET","/api/v1/routes/R-BLR-07/seats",token("globex.admin"),null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/api/v1/bookings",jwt(Role.HR_SYSTEM),"{\"employeeId\":\"MISSING\",\"pickupAt\":\"2026-11-01T12:00:00Z\"}").statusCode()).isEqualTo(403);
    }
}
