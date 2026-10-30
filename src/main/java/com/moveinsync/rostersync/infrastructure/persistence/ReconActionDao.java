package com.moveinsync.rostersync.infrastructure.persistence;
import com.moveinsync.rostersync.domain.recon.*;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReconActionDao {
    private final JdbcTemplate jdbc;private final JdbcClient client;private final JsonMapper mapper;private final Clock clock;
    public ReconActionDao(JdbcTemplate jdbc,JdbcClient client,JsonMapper mapper,Clock clock){this.jdbc=jdbc;this.client=client;this.mapper=mapper;this.clock=clock;}
    @Transactional
    public void insert(UUID tenantId,UUID runId,List<PlannedAction> actions) {
        jdbc.batchUpdate("INSERT INTO recon_action(tenant_id,run_id,external_id,action,file_version,expected_db_version,payload) VALUES (?,?,?,?,?,?,CAST(? AS jsonb))",
                actions.stream().map(action->new Object[]{tenantId,runId,action.employeeId(),action.action().name(),action.fileVersion(),action.expectedDbVersion(),
                        action.payload()==null?null:mapper.writeValueAsString(action.payload())}).toList());
    }
    @Transactional
    public void rejections(UUID tenantId,UUID runId,List<RejectedRow> rejected) {
        jdbc.batchUpdate("INSERT INTO record_rejection(tenant_id,source,run_id,row_number,external_id,error_codes,created_at) VALUES (?,'FILE',?,?,?,?,?)",
                rejected.stream().map(row->new Object[]{tenantId,runId,row.number(),row.employeeId(),row.codes(),Timestamp.from(clock.instant())}).toList());
    }
    public List<Action> pending(UUID tenantId,UUID runId,int size,boolean lock) {
        return client.sql("SELECT id,external_id,action,file_version,expected_db_version,payload::text FROM recon_action WHERE tenant_id=? AND run_id=? AND status='PENDING' ORDER BY external_id LIMIT ?"+(lock?" FOR UPDATE":""))
                .params(tenantId,runId,size).query((row,n)->new Action(row.getLong(1),row.getString(2),ActionType.valueOf(row.getString(3)),(Long)row.getObject(4),(Long)row.getObject(5),
                        row.getString(6)==null?null:mapper.readValue(row.getString(6),HrSnapshot.class))).list();
    }
    public void complete(UUID tenantId,UUID runId,long actionId,String status,String reason) {
        client.sql("UPDATE recon_action SET status=?,outcome_reason=? WHERE tenant_id=? AND run_id=? AND id=? AND status='PENDING'").params(status,reason,tenantId,runId,actionId).update();
    }
    public record RejectedRow(long number,String employeeId,String codes) { }
    public record Action(long id,String employeeId,ActionType type,Long fileVersion,Long expectedVersion,HrSnapshot snapshot) { }
}
