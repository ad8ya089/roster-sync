package com.moveinsync.rostersync.application.ops;

import com.moveinsync.rostersync.api.error.NotFoundException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
    private final JdbcClient jdbc; private final Clock clock;
    public AlertService(JdbcClient jdbc,Clock clock) {this.jdbc=jdbc;this.clock=clock;}
    @Transactional(readOnly=true)
    public List<AlertView> list(UUID tenantId,boolean acknowledged) {
        return jdbc.sql("SELECT id,alert_type,reference_id,message,created_at,acknowledged_at FROM ops_alert WHERE tenant_id=? AND (acknowledged_at IS NOT NULL)=? ORDER BY id DESC LIMIT 200")
                .params(tenantId,acknowledged).query((row,n)->new AlertView(row.getLong(1),row.getString(2),row.getString(3),row.getString(4),row.getTimestamp(5).toInstant(),
                        row.getTimestamp(6)==null?null:row.getTimestamp(6).toInstant())).list();
    }
    @Transactional
    public void acknowledge(UUID tenantId,long id,String username) {
        int rows=jdbc.sql("UPDATE ops_alert SET acknowledged_at=COALESCE(acknowledged_at,?),acknowledged_by=COALESCE(acknowledged_by,?) WHERE tenant_id=? AND id=?")
                .params(Timestamp.from(clock.instant()),username,tenantId,id).update();
        if(rows==0) throw new NotFoundException();
    }
    public record AlertView(long id,String alertType,String referenceId,String message,Instant createdAt,Instant acknowledgedAt) { }
}
