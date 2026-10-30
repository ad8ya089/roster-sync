package com.moveinsync.rostersync.application.outbox;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.domain.outbox.OutboxEventType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    public OutboxWriter(JdbcClient jdbc, JsonMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void writeReconBrake(UUID tenantId,UUID runId,String message,Instant at) {
        UUID eventId=UUID.randomUUID();
        var payload=Map.of("eventId",eventId,"eventType","ReconciliationBrakeTripped","tenantId",tenantId,"runId",runId,"occurredAt",at,"message",message);
        jdbc.sql("INSERT INTO outbox_event(event_id,tenant_id,aggregate_type,aggregate_id,event_type,payload,next_attempt_at,created_at) VALUES (?,?,'ReconciliationRun',?,'ReconciliationBrakeTripped',CAST(? AS jsonb),?,?)")
                .params(eventId,tenantId,runId.toString(),mapper.writeValueAsString(payload),Timestamp.from(at),Timestamp.from(at)).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID tenantId, String employeeId, long version, OutboxEventType type, String source,
                      List<EmployeeField> changedFields, HrSnapshot snapshot, Instant at) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> values = new LinkedHashMap<>();
        if (type != OutboxEventType.TRANSPORT_PROFILE_UPDATED) {
            values.put("officeCode", snapshot.officeCode()); values.put("shiftCode", snapshot.shiftCode());
            values.put("status", snapshot.status()); values.put("managerId", snapshot.managerId());
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId); payload.put("eventType", type.wireName()); payload.put("tenantId", tenantId);
        payload.put("employeeId", employeeId); payload.put("version", version); payload.put("occurredAt", at);
        payload.put("source", source); payload.put("changedFields", changedFields.stream().map(EmployeeField::jsonName).toList()); payload.put("values", values);
        // JpaTransactionManager binds the same DataSource connection for EntityManager and JdbcClient.
        // JSONB goes through an explicit PostgreSQL cast and Boot's Jackson 3 mapper, never Hibernate JSON.
        jdbc.sql("""
                INSERT INTO outbox_event(event_id,tenant_id,aggregate_type,aggregate_id,aggregate_version,
                  event_type,payload,next_attempt_at,created_at) VALUES(?,?,'Employee',?,?,?,CAST(? AS jsonb),?,?)
                """).params(eventId, tenantId, employeeId, version, type.wireName(), mapper.writeValueAsString(payload),
                        Timestamp.from(at), Timestamp.from(at)).update();
    }
}
