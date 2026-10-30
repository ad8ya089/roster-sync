package com.moveinsync.rostersync.application.ops;

import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.application.employee.PageResponse;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxOpsService {
    public enum Status { PENDING,PUBLISHED,DEAD }
    private final JdbcClient jdbc; private final Clock clock;
    public OutboxOpsService(JdbcClient jdbc,Clock clock) {this.jdbc=jdbc;this.clock=clock;}
    @Transactional(readOnly=true)
    public PageResponse<OutboxRow> list(UUID tenantId,Status status,int page,int size) {
        String selected=status==null?null:status.name();
        var items=jdbc.sql("""
                SELECT id,event_id,aggregate_id,event_type,status,attempts,next_attempt_at,last_error,created_at,published_at
                FROM outbox_event WHERE tenant_id=? AND (CAST(? AS varchar) IS NULL OR status=?) ORDER BY id DESC LIMIT ? OFFSET ?
                """).params(tenantId,selected,selected,size,(long)page*size).query((row,n)->new OutboxRow(row.getLong(1),row.getObject(2,UUID.class),
                        row.getString(3),row.getString(4),row.getString(5),row.getInt(6),row.getTimestamp(7).toInstant(),row.getString(8),
                        row.getTimestamp(9).toInstant(),row.getTimestamp(10)==null?null:row.getTimestamp(10).toInstant())).list();
        long total=jdbc.sql("SELECT count(*) FROM outbox_event WHERE tenant_id=? AND (CAST(? AS varchar) IS NULL OR status=?)").params(tenantId,selected,selected).query(Long.class).single();
        return new PageResponse<>(items,page,size,total);
    }
    @Transactional
    public void retry(UUID tenantId,long id) {
        var status=jdbc.sql("SELECT status FROM outbox_event WHERE tenant_id=? AND id=? FOR UPDATE").params(tenantId,id).query(String.class).optional().orElseThrow(NotFoundException::new);
        if(!"DEAD".equals(status)) throw new ConflictException(ErrorCode.INVALID_OUTBOX_STATE);
        jdbc.sql("UPDATE outbox_event SET status='PENDING',attempts=0,next_attempt_at=?,last_error=NULL WHERE tenant_id=? AND id=?")
                .params(Timestamp.from(clock.instant()),tenantId,id).update();
    }
    public record OutboxRow(long id,UUID eventId,String employeeId,String eventType,String status,int attempts,Instant nextAttemptAt,String lastError,Instant createdAt,Instant publishedAt) { }
}
