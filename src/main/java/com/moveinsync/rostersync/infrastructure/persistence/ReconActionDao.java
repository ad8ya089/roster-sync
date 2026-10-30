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
    private final com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics;
    public ReconActionDao(JdbcTemplate jdbc,JdbcClient client,JsonMapper mapper,Clock clock,com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics metrics){this.jdbc=jdbc;this.client=client;this.mapper=mapper;this.clock=clock;this.metrics=metrics;}
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
        com.moveinsync.rostersync.infrastructure.metrics.RosterMetrics.committed(()->rejected.forEach(row->{
            metrics.fileRowRejected();Arrays.stream(row.codes().split(",")).map(code->code.substring(code.lastIndexOf(':')+1)).distinct().forEach(code->metrics.rejection(code,"file"));
        }));
    }
    public List<Action> pending(UUID tenantId,UUID runId,int size,boolean lock) {
        return client.sql("SELECT id,external_id,action,file_version,expected_db_version,payload::text FROM recon_action WHERE tenant_id=? AND run_id=? AND status='PENDING' ORDER BY external_id LIMIT ?"+(lock?" FOR UPDATE":""))
                .params(tenantId,runId,size).query((row,n)->new Action(row.getLong(1),row.getString(2),ActionType.valueOf(row.getString(3)),(Long)row.getObject(4),(Long)row.getObject(5),
                        row.getString(6)==null?null:mapper.readValue(row.getString(6),HrSnapshot.class))).list();
    }
    public void complete(UUID tenantId,UUID runId,long actionId,String status,String reason) {
        client.sql("UPDATE recon_action SET status=?,outcome_reason=? WHERE tenant_id=? AND run_id=? AND id=? AND status='PENDING'").params(status,reason,tenantId,runId,actionId).update();
    }
    public void completeBatch(UUID tenantId,UUID runId,Map<Long,String> outcomes){
        if(outcomes.isEmpty())return;
        if(!outcomes.values().stream().allMatch(Set.of("APPLIED","SUPERSEDED","SKIPPED")::contains))throw new IllegalArgumentException("Invalid action result");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)connection->{
            var applied=connection.createArrayOf("bigint",outcomes.entrySet().stream().filter(entry->entry.getValue().equals("APPLIED")).map(Map.Entry::getKey).toArray());
            var superseded=connection.createArrayOf("bigint",outcomes.entrySet().stream().filter(entry->entry.getValue().equals("SUPERSEDED")).map(Map.Entry::getKey).toArray());
            var ids=connection.createArrayOf("bigint",outcomes.keySet().toArray());
            try(var statement=connection.prepareStatement("""
                    UPDATE recon_action SET status=CASE WHEN id=ANY(?) THEN 'APPLIED' WHEN id=ANY(?) THEN 'SUPERSEDED' ELSE 'SKIPPED' END,outcome_reason=NULL
                    WHERE tenant_id=? AND run_id=? AND id=ANY(?) AND status='PENDING'
                    """)){
                statement.setArray(1,applied);statement.setArray(2,superseded);statement.setObject(3,tenantId);statement.setObject(4,runId);statement.setArray(5,ids);
                int changed=statement.executeUpdate();if(changed!=outcomes.size())throw new IllegalStateException("Action claim changed");return changed;
            }finally{applied.free();superseded.free();ids.free();}
        });
    }
    public record RejectedRow(long number,String employeeId,String codes) { }
    public record Action(long id,String employeeId,ActionType type,Long fileVersion,Long expectedVersion,HrSnapshot snapshot) { }
}
