package com.moveinsync.rostersync.application.recon;
import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.hash.RecordHasher;
import com.moveinsync.rostersync.domain.ownership.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.recon.*;
import com.moveinsync.rostersync.domain.validation.OfficeLookup;
import com.moveinsync.rostersync.infrastructure.csv.*;
import com.moveinsync.rostersync.infrastructure.persistence.*;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationRunner {
    private static final Logger LOG=LoggerFactory.getLogger(ReconciliationRunner.class);
    private final ReconRunDao runs;private final ReconActionDao actions;private final EmployeeBucketDao employees;
    private final RosterCsvReader csv;private final FieldOwnershipPolicy ownership;private final RecordHasher hasher;
    private final SettingsService settings;private final RosterProperties properties;private final JdbcClient jdbc;
    private final ObjectProvider<ReconApplyWork> applier;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public ReconciliationRunner(ReconRunDao runs,ReconActionDao actions,EmployeeBucketDao employees,RosterCsvReader csv,FieldOwnershipPolicy ownership,RecordHasher hasher,
                                SettingsService settings,RosterProperties properties,JdbcClient jdbc,ObjectProvider<ReconApplyWork> applier,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.runs=runs;this.actions=actions;this.employees=employees;this.csv=csv;this.ownership=ownership;this.hasher=hasher;this.settings=settings;this.properties=properties;this.jdbc=jdbc;this.applier=applier;this.metrics=metrics;
    }
    public void run(UUID tenantId,UUID runId) {
        var previous=MDC.getCopyOfContextMap();MDC.put("tenantId",tenantId.toString());MDC.put("runId",runId.toString());
        var run=runs.get(tenantId,runId);Path directory=run.file().resolveSibling(runId.toString());
        try {
            if(run.status().equals("APPLYING")){applier.getObject().resume(tenantId,runId);return;}
            if(!run.status().equals("UPLOADED")) return;
            runs.transition(tenantId,runId,"UPLOADED","DIFFING");
            var brakeSettings=settings.get(tenantId).brake();runs.settings(tenantId,runId,brakeSettings);
            long ignoredColumns=csv.header(run.file()).stream().filter(column->!RosterCsvReader.COLUMNS.contains(column)).count();
            if(ignoredColumns>0) LOG.warn("recon.file.warning runId={} code=UNKNOWN_FIELD_IGNORED ignoredColumns={}",runId,ignoredColumns);
            // This small per-run office catalogue avoids 200k point queries. Apply rechecks activity.
            Map<String,OfficeLookup.Status> offices=new HashMap<>();
            jdbc.sql("SELECT code,active FROM office WHERE tenant_id=?").param(tenantId).query((row,n)->{
                offices.put(row.getString(1),row.getBoolean(2)?OfficeLookup.Status.ACTIVE:OfficeLookup.Status.INACTIVE);return 0;
            }).list();
            var rejects=new ArrayList<ReconActionDao.RejectedRow>();long[] parsed={0,0};
            RosterCsvReader.ReadSummary summary;
            int buckets=properties.reconciliation().buckets();
            try(var spills=new BucketSpillWriter(directory,buckets,hasher)) {
                summary=csv.read(run.file(),tenantId,ownership,(ignored,code)->offices.getOrDefault(code,OfficeLookup.Status.UNKNOWN),row->{
                    parsed[0]++;if(row.snapshot()!=null) parsed[1]++;
                    if(!row.errors().isEmpty()) rejects.add(new ReconActionDao.RejectedRow(row.number(),row.employeeId(),row.errors().stream()
                            .map(error->error.field()+":"+error.code()).distinct().sorted().collect(Collectors.joining(","))));
                    try{spills.write(row);}catch(IOException failure){throw new IllegalStateException("SPILL_IO");}
                    flushRejects(tenantId,runId,rejects,false);
                    if(parsed[0]%1000==0) runs.progress(tenantId,runId,parsed[0],parsed[1],null,DiffCounters.zero());
                    if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("INTERRUPTED");
                });
            }
            flushRejects(tenantId,runId,rejects,true);
            var counts=DiffCounters.zero();long duplicates=0;var differ=new BucketDiffer(hasher);
            for(int bucket=0;bucket<buckets;bucket++) {
                if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("INTERRUPTED");
                var file=BucketSpillWriter.file(directory,bucket);
                var contents=new BucketSpillReader().read(file,(number,id)->{
                    rejects.add(new ReconActionDao.RejectedRow(number,id,"employeeId:DUPLICATE_ROW_IN_FILE"));flushRejects(tenantId,runId,rejects,false);
                });
                var diff=differ.diff(contents.rows(),employees.findBucket(tenantId,bucket),contents.presentButInvalid(),run.asOf());
                int batch=properties.reconciliation().insertBatchSize();
                for(int start=0;start<diff.actions().size();start+=batch) actions.insert(tenantId,runId,diff.actions().subList(start,Math.min(start+batch,diff.actions().size())));
                counts=counts.plus(diff.counters());duplicates+=contents.duplicateValidRows();
                flushRejects(tenantId,runId,rejects,true);
                runs.progress(tenantId,runId,summary.total(),summary.valid()-duplicates,summary.trailerCount(),counts);Files.deleteIfExists(file);
            }
            var brake=new SafetyBrake().evaluate(new BrakeInput(counts.activeBefore(),counts.missingActive(),summary.total(),summary.trailerCount(),
                    run.expectedRowCount()==null?null:run.expectedRowCount().longValue()),brakeSettings);
            runs.finishDiff(tenantId,runId,brake,run.dryRun());
            if(brake.tripped()) {metrics.counter("recon.brake.tripped").increment();LOG.warn("recon.run.brake_tripped runId={} activeBefore={} missingActive={}",runId,counts.activeBefore(),counts.missingActive());}
            if(!run.dryRun() && !brake.tripped()) applier.getObject().resume(tenantId,runId);
        } catch(RosterCsvReader.MalformedCsv malformed) {
            actions.rejections(tenantId,runId,List.of(new ReconActionDao.RejectedRow(malformed.number(),null,"record:MALFORMED_CSV")));
            failIfRunning(tenantId,runId,"MALFORMED_CSV");
        } catch(Exception failure) {
            LOG.error("recon.run.failed runId={} exceptionClass={}",runId,failure.getClass().getSimpleName());
            if(!Thread.currentThread().isInterrupted()) failIfRunning(tenantId,runId,"PROCESSING_FAILED");
        } finally {
            try{deleteSpills(directory);}catch(IOException failure){LOG.warn("recon.spill.cleanup_failed runId={}",runId);}
            if(previous==null) MDC.clear();else MDC.setContextMap(previous);
        }
    }
    private void failIfRunning(UUID tenantId,UUID runId,String reason) {
        var state=runs.get(tenantId,runId).status();if(Set.of("UPLOADED","DIFFING").contains(state)) runs.fail(tenantId,runId,state,reason);
    }
    private void flushRejects(UUID tenantId,UUID runId,List<ReconActionDao.RejectedRow> rejected,boolean force) {
        if(!rejected.isEmpty() && (force || rejected.size()>=1000)){actions.rejections(tenantId,runId,rejected);rejected.clear();}
    }
    public static void deleteSpills(Path directory) throws IOException {
        if(!Files.exists(directory)) return;
        UUID.fromString(directory.toAbsolutePath().normalize().getFileName().toString());
        // Only the generated UUID spill directory is deleted; uploaded originals are retained.
        try(var files=Files.walk(directory)){for(var path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
    }
}
