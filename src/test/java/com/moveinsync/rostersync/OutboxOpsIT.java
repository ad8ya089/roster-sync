package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.ops.*;
import com.moveinsync.rostersync.domain.model.Role;
import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class OutboxOpsIT extends AbstractOutboxIntegrationTest {
    @Autowired RetentionJob retention;
    @Test void operationsAreTenantScopedAndAcknowledgementIsIdempotent() throws Exception {
        long id=pending("OPS","EmployeeUpdated");jdbc.update("UPDATE outbox_event SET status='DEAD' WHERE id=?",id);
        String globex=token("globex.operator"),acme=jwt(Role.OPERATOR);
        assertThat(request("POST","/api/v1/ops/outbox/"+id+"/retry",globex,null).statusCode()).isEqualTo(404);
        assertThat(request("GET","/api/v1/ops/outbox",globex,null).body()).contains("\"totalElements\":0");
        assertThat(request("POST","/api/v1/ops/outbox/"+id+"/retry",jwt(Role.TRANSPORT_ADMIN),null).statusCode()).isEqualTo(403);
        assertThat(request("POST","/api/v1/ops/outbox/"+id+"/retry",acme,null).statusCode()).isEqualTo(204);
        problem(request("POST","/api/v1/ops/outbox/"+id+"/retry",acme,null),409,"INVALID_OUTBOX_STATE");
        long alert=jdbc.queryForObject("INSERT INTO ops_alert(tenant_id,alert_type,message) VALUES (?,'OUTBOX_EVENT_DEAD','Safe alert') RETURNING id",Long.class,JwtTestTokens.ACME);
        assertThat(request("POST","/api/v1/ops/alerts/"+alert+"/ack",globex,null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/api/v1/ops/alerts/"+alert+"/ack",acme,null).statusCode()).isEqualTo(204);
        String before=jdbc.queryForObject("SELECT row_to_json(ops_alert)::text FROM ops_alert",String.class);
        clock.advance(Duration.ofMinutes(1));request("POST","/api/v1/ops/alerts/"+alert+"/ack",acme,null);
        assertThat(jdbc.queryForObject("SELECT row_to_json(ops_alert)::text FROM ops_alert",String.class)).isEqualTo(before);
    }
    @Test void faultsAreOperatorOnlyVisibleAndOneShot() throws Exception {
        String body="{\"point\":\"OUTBOX_BEFORE_DISPATCH\",\"mode\":\"THROW\"}";
        assertThat(request("POST","/api/v1/dev/faults",jwt(Role.TRANSPORT_ADMIN),body).statusCode()).isEqualTo(403);
        assertThat(request("POST","/api/v1/dev/faults",jwt(Role.OPERATOR),body).statusCode()).isEqualTo(200);
        assertThat(request("GET","/api/v1/dev/faults",jwt(Role.OPERATOR),null).body()).contains("OUTBOX_BEFORE_DISPATCH","\"oneShot\":true");
        long id=pending("FAULT","EmployeeUpdated");publisher.tick();assertThat(status(id)).isEqualTo("PENDING");
        assertThat(request("GET","/api/v1/dev/faults",jwt(Role.OPERATOR),null).body()).isEqualTo("[]");
        publisher.tick();assertThat(status(id)).isEqualTo("PUBLISHED");
    }
    @Test void retentionLoopsAcrossFiveThousandRowsAndKeepsPendingAndDead() {
        var old=Timestamp.from(clock.instant().minus(Duration.ofDays(40)));
        jdbc.update("INSERT INTO processed_event(sink,event_id,processed_at) SELECT 'retention',md5(number::text)::uuid,? FROM generate_series(1,5001) number",old);
        long pending=pending("KEEP","EmployeeUpdated"),dead=pending("DEAD","EmployeeUpdated"),published=pending("OLD","EmployeeUpdated");
        jdbc.update("UPDATE outbox_event SET status='DEAD' WHERE id=?",dead);
        jdbc.update("UPDATE outbox_event SET status='PUBLISHED',published_at=? WHERE id=?",old,published);
        retention.run();assertThat(count("processed_event")).isZero();assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(status(pending)).isEqualTo("PENDING");assertThat(status(dead)).isEqualTo("DEAD");
    }
}
