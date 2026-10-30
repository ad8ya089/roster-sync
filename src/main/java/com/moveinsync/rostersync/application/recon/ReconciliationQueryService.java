package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.api.error.NotFoundException;
import com.moveinsync.rostersync.application.employee.PageResponse;
import com.moveinsync.rostersync.domain.model.Role;
import com.moveinsync.rostersync.domain.recon.*;
import com.moveinsync.rostersync.infrastructure.persistence.ReconRunDao;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class ReconciliationQueryService {
    private final ReconRunDao runs;private final JdbcClient jdbc;
    public ReconciliationQueryService(ReconRunDao runs,JdbcClient jdbc){this.runs=runs;this.jdbc=jdbc;}
    public RunView get(UUID tenantId,UUID id,Role role,String username) {
        var row=runs.metadata(tenantId,id);if(role==Role.HR_SYSTEM && !username.equals(row.get("created_by"))) throw new NotFoundException();
        long active=number(row,"active_before"),missing=number(row,"missing_active");
        var ratio=(BigDecimal)row.get("brake_ratio_used");
        var decision=new SafetyBrake().evaluate(new BrakeInput(active,missing,number(row,"rows_total"),nullable(row,"trailer_row_count"),nullable(row,"expected_row_count")),
                new BrakeSettings(ratio==null?new BigDecimal("0.300"):ratio,nullable(row,"brake_count_used")));
        return new RunView(id,(String)row.get("status"),(Boolean)row.get("dry_run"),instant(row,"as_of"),
                new Rows(number(row,"rows_total"),number(row,"rows_valid"),number(row,"rows_rejected")),
                new Plan(number(row,"planned_creates"),number(row,"planned_updates"),number(row,"planned_heals"),number(row,"planned_advances"),number(row,"unchanged"),number(row,"stale_skipped"),number(row,"planned_deactivations")),
                new Brake((Boolean)row.get("brake_tripped"),(String)row.get("brake_reason"),decision.reasons(),active,missing,decision.ratio(),ratio,nullable(row,"brake_count_used"),decision.message()),
                number(row,"applied"),number(row,"superseded"),new Timings(duration(row,"diff_started_at","diff_finished_at"),
                        "COMPLETED".equals(row.get("status"))?duration(row,row.get("decided_at")==null?"diff_finished_at":"decided_at","finished_at"):null),(String)row.get("failure_reason"));
    }
    public PageResponse<RunView> list(UUID tenantId,String status,int page,int size,Role role,String username) {
        String owner=role==Role.HR_SYSTEM?username:null;
        String where=" WHERE tenant_id=? AND (CAST(? AS varchar) IS NULL OR status=?) AND (CAST(? AS varchar) IS NULL OR created_by=?)";
        var ids=jdbc.sql("SELECT id FROM recon_run"+where+" ORDER BY created_at DESC LIMIT ? OFFSET ?").params(tenantId,status,status,owner,owner,size,(long)page*size).query(UUID.class).list();
        long total=jdbc.sql("SELECT count(*) FROM recon_run"+where).params(tenantId,status,status,owner,owner).query(Long.class).single();
        return new PageResponse<>(ids.stream().map(id->get(tenantId,id,role,username)).toList(),page,size,total);
    }
    public PageResponse<Rejection> rejections(UUID tenantId,UUID runId,int page,int size,Role role,String username) {
        get(tenantId,runId,role,username);
        var rows=jdbc.sql("SELECT row_number,external_id,error_codes FROM record_rejection WHERE tenant_id=? AND run_id=? ORDER BY row_number,id LIMIT ? OFFSET ?")
                .params(tenantId,runId,size,(long)page*size).query((row,n)->new Rejection(row.getLong(1),row.getString(2),List.of(row.getString(3).split(",")))).list();
        long total=jdbc.sql("SELECT count(*) FROM record_rejection WHERE tenant_id=? AND run_id=?").params(tenantId,runId).query(Long.class).single();
        return new PageResponse<>(rows,page,size,total);
    }
    private static long number(Map<String,Object> row,String key){return row.get(key)==null?0:((Number)row.get(key)).longValue();}
    private static Long nullable(Map<String,Object> row,String key){return row.get(key)==null?null:number(row,key);}
    private static Instant instant(Map<String,Object> row,String key){return row.get(key)==null?null:((Timestamp)row.get(key)).toInstant();}
    private static Long duration(Map<String,Object> row,String start,String end){return instant(row,start)==null || instant(row,end)==null?null:Duration.between(instant(row,start),instant(row,end)).toMillis();}
    public record RunView(UUID runId,String status,boolean dryRun,Instant asOf,Rows rows,Plan plan,Brake brake,long applied,long superseded,Timings timings,String failureReason) { }
    public record Rows(long total,long valid,long rejected) { }
    public record Plan(long creates,long updates,long heals,long advances,long unchanged,long staleSkipped,long deactivations) { }
    public record Brake(boolean tripped,String reason,List<String> reasons,long activeBefore,long missingActive,BigDecimal missingRatio,BigDecimal thresholdRatio,Long thresholdCount,String message) { }
    public record Timings(Long diffMs,Long applyMs) { }
    public record Rejection(long rowNumber,String employeeId,List<String> errorCodes) { }
}
