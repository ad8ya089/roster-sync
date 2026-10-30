package com.moveinsync.rostersync;
import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.application.recon.*;
import com.moveinsync.rostersync.domain.model.Role;
import com.moveinsync.rostersync.domain.recon.BucketPartitioner;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class ReconciliationFlowIT extends AbstractReconciliationIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired com.moveinsync.rostersync.application.outbox.OutboxPublisher publisher;
    @Test void healthyRunCompletes() throws Exception {
        removeFixtureEmployees();send(event("R1",1,"CREATE","BLR-1","S1"));
        var run=finished(upload(header()+row("R1",2,"S2")+row("NEW",1,"S1"),false));
        assertThat(run.status()).isEqualTo("COMPLETED");assertThat(run.plan().updates()).isEqualTo(1);assertThat(run.plan().creates()).isEqualTo(1);
        assertThat(version("R1")).isEqualTo(2);assertThat(count("hr_event_receipt")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE payload->>'source'='RECON'",Long.class)).isEqualTo(2);
    }
    @Test @Tag("slow") @Timeout(300) void truncatedFileBriefExample() throws Exception {
        removeFixtureEmployees();long start=System.nanoTime();seed.seed(JwtTestTokens.ACME,200000);long seedMs=(System.nanoTime()-start)/1000000;
        // Exercise real chunk duration on the 200k store before proving the truncation brake.
        var healthy=temporary.resolve("healthy.csv");RosterFileGenerator.Expected truth;
        try(var output=Files.newOutputStream(healthy)){truth=exporter.write(JwtTestTokens.ACME,output,new RosterFileGenerator.Spec(null,new BigDecimal("1"),BigDecimal.ZERO,BigDecimal.ZERO,20261030));}
        var healthyId=uploads.upload(JwtTestTokens.ACME,"fixture",new PathUpload(healthy),Instant.now(),null,false).runId();
        var complete=finished(healthyId,Duration.ofSeconds(180));assertThat(complete.status()).isEqualTo("COMPLETED");assertThat(complete.plan().updates()).isEqualTo(truth.updates());
        assertThat(complete.timings().diffMs()).isLessThan(60000);
        assertThat(applier.longestChunkMillis()).isLessThan(5000);
        var truncated=temporary.resolve("truncated.csv");try(var output=Files.newOutputStream(truncated)){exporter.write(JwtTestTokens.ACME,output,new RosterFileGenerator.Spec(120000L,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,20261030));}
        var id=uploads.upload(JwtTestTokens.ACME,"fixture",new PathUpload(truncated),Instant.now(),null,false).runId();var run=finished(id);
        assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");assertThat(run.brake().missingActive()).isEqualTo(80000);
        assertThat(run.brake().message()).contains("80,000 of 200,000","40.0%");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE status='ACTIVE'",Long.class)).isEqualTo(200000);
        assertThat(run.rows().total()).isEqualTo(120000);
        assertThat(run.timings().diffMs()).isLessThan(60000);
        jdbc.query("SELECT external_id,recon_bucket FROM employee WHERE tenant_id=?",(org.springframework.jdbc.core.RowCallbackHandler)row->assertThat(row.getInt(2)).isEqualTo(BucketPartitioner.bucketOf(row.getString(1),256)),JwtTestTokens.ACME);
        Files.writeString(Path.of("target/phase6-200k-timings.txt"),"seedMs="+seedMs+" healthyDiffMs="+complete.timings().diffMs()+" healthyApplyMs="+complete.timings().applyMs()+" truncatedDiffMs="+run.timings().diffMs()+" longestChunkMs="+applier.longestChunkMillis()+" seed=20261030 updates="+truth.updates());
        // Seed latency is recorded here; its <20s local-runtime target is checked against
        // Compose separately, without JaCoCo/cached test contexts or concurrent image builds.
    }
    @Test void badRowsAreReportedAndCountAsPresent() throws Exception {
        removeFixtureEmployees();send(event("BAD",1,"CREATE","BLR-1","S1"));
        var run=finished(upload(header()+row("BAD",2,"S1").replace("+919800000001","bad phone"),false));
        assertThat(run.status()).isEqualTo("COMPLETED");assertThat(run.rows().rejected()).isEqualTo(1);assertThat(run.plan().deactivations()).isZero();assertThat(version("BAD")).isEqualTo(1);
    }
    @Test void trailerMismatchForcesBrake() throws Exception {var run=finished(upload(export(RosterFileGenerator.Spec.unchanged()).replace("row_count=5","row_count=9"),false));assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");assertThat(run.brake().reason()).isEqualTo("ROW_COUNT_MISMATCH");}
    @Test void invalidHeaderFails() throws Exception {
        long before=count("recon_run");problem(multipart("employee_id,version\nE1,1\n",Instant.now().toString(),jwt(Role.HR_SYSTEM)),400,"INVALID_CSV_HEADER");
        problem(multipart("",Instant.now().toString(),jwt(Role.HR_SYSTEM)),400,"VALIDATION_FAILED");
        problem(request("POST","/api/v1/reconciliation/runs",jwt(Role.HR_SYSTEM),"--missing-file\r\nContent-Disposition: form-data; name=\"asOf\"\r\n\r\n"+Instant.now()+"\r\n--missing-file--\r\n","multipart/form-data; boundary=missing-file"),400,"VALIDATION_FAILED");
        problem(multipart(header(),Instant.now().plusSeconds(301).toString(),jwt(Role.HR_SYSTEM)),400,"VALIDATION_FAILED");assertThat(count("recon_run")).isEqualTo(before);
    }
    @Test void dryRunOnlyPlans() throws Exception {var run=finished(upload(header()+row("X",1,"S1"),true));assertThat(run.status()).isEqualTo("PLANNED");assertThat(count("employee")).isEqualTo(5);assertThatThrownBy(()->decisions.decide(JwtTestTokens.ACME,run.runId(),true,"ALL","fixture",null)).isInstanceOf(InvalidStateException.class);}
    @Test void approveAllApplies() throws Exception {var run=finished(upload(header()+row("X",1,"S1"),false));decisions.decide(JwtTestTokens.ACME,run.runId(),true,"ALL","fixture",null);assertThat(finished(run.runId()).status()).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE status='INACTIVE'",Long.class)).isEqualTo(5);}
    @Test void approveSkipDeactivations() throws Exception {var run=finished(upload(header()+row("X",1,"S1"),false));decisions.decide(JwtTestTokens.ACME,run.runId(),true,"SKIP_DEACTIVATIONS","fixture",null);assertThat(finished(run.runId()).status()).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE status='ACTIVE'",Long.class)).isEqualTo(6);assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE status='SKIPPED'",Long.class)).isEqualTo(5);}
    @Test void rejectDiscards() throws Exception {var run=finished(upload(header()+row("X",1,"S1"),false));decisions.decide(JwtTestTokens.ACME,run.runId(),false,null,"fixture","safe note");assertThat(finished(run.runId()).status()).isEqualTo("REJECTED");assertThat(count("employee")).isEqualTo(5);assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE status='PENDING'",Long.class)).isZero();}
    @Test void thresholdChangeTakesEffect() throws Exception {
        String csv=export(RosterFileGenerator.Spec.unchanged());var lines=csv.lines().filter(line->!line.startsWith("E1004,") && !line.startsWith("E1005,") && !line.startsWith("#TRAILER") && !line.startsWith("\"#TRAILER\"")).toList();String cut=String.join("\n",lines)+"\n";
        var first=finished(upload(cut,false));assertThat(first.status()).isEqualTo("AWAITING_APPROVAL");decisions.decide(JwtTestTokens.ACME,first.runId(),false,null,"fixture",null);
        assertThat(request("PUT","/api/v1/settings/reconciliation",jwt(Role.OPERATOR),"{\"maxMissingRatio\":0.5}").statusCode()).isEqualTo(200);
        assertThat(finished(upload(cut,false)).status()).isEqualTo("COMPLETED");
    }
    @Test void employeeCreatedAfterAsOfIsNotDeactivated() throws Exception {var run=finished(upload(header()+row("NEW",1,"S1"),Instant.now().minusSeconds(60),false,null));assertThat(run.plan().deactivations()).isZero();assertThat(run.status()).isEqualTo("COMPLETED");}
    @Test void advanceHigherVersionSameContent() throws Exception {removeFixtureEmployees();send(event("ADV",7,"CREATE","BLR-1","S1"));long outbox=employeeOutbox();var run=finished(upload(header()+row("ADV",8,"S1"),false));assertThat(run.plan().advances()).isEqualTo(1);assertThat(run.plan().updates()).isZero();assertThat(version("ADV")).isEqualTo(8);assertThat(employeeOutbox()).isEqualTo(outbox);assertThat(send(event("ADV",6,"UPDATE","BLR-1","S2")).summary().stale()).isEqualTo(1);}
    @Test void duplicateIdsInFile() throws Exception {removeFixtureEmployees();send(event("SAME",1,"CREATE","BLR-1","S1"));var run=finished(upload(header()+row("WIN",1,"S1")+row("WIN",3,"S2")+row("SAME",2,"S1")+row("SAME",2,"S2"),false));assertThat(version("WIN")).isEqualTo(3);assertThat(version("SAME")).isEqualTo(1);assertThat(run.rows().rejected()).isEqualTo(2);assertThat(run.plan().deactivations()).isZero();}
    @Test void staleExportRejected() throws Exception {Instant asOf=Instant.ofEpochSecond(Instant.now().plusSeconds(1).getEpochSecond(),123456123);assertThat(finished(upload(export(RosterFileGenerator.Spec.unchanged()),asOf,false,null)).status()).isEqualTo("COMPLETED");assertThatThrownBy(()->upload(header(),asOf,false,null)).isInstanceOfSatisfying(ConflictException.class,error->assertThat(error.errorCode()).isEqualTo(ErrorCode.RECON_EXPORT_STALE));assertThat(count("recon_run")).isEqualTo(1);}
    @Test void malformedCsvFails() throws Exception {var run=finished(upload(header()+"E1,1,\"PII_CANARY_UNCLOSED",false));assertThat(run.status()).isEqualTo("FAILED");assertThat(run.failureReason()).isEqualTo("MALFORMED_CSV");assertThat(jdbc.queryForObject("SELECT error_codes FROM record_rejection",String.class)).doesNotContain("CANARY");}
    @Test void reconDeactivationCancelsBookings() throws Exception {
        String csv=export(RosterFileGenerator.Spec.unchanged());csv=String.join("\n",csv.lines().filter(line->!line.startsWith("E1002,") && !line.startsWith("#TRAILER") && !line.startsWith("\"#TRAILER\"")).toList())+"\n";
        var run=finished(upload(csv,false));assertThat(run.status()).isEqualTo("COMPLETED");publisher.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cab_booking WHERE status='CANCELLED' AND cancel_reason='EMPLOYEE_DEACTIVATED'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT deactivation_source FROM employee WHERE tenant_id=? AND external_id='E1002'",String.class,JwtTestTokens.ACME)).isEqualTo("RECON");
        assertThat(version("E1002")).isEqualTo(1);
    }
    public static class PathUpload implements org.springframework.web.multipart.MultipartFile {
        private final Path path;public PathUpload(Path path){this.path=path;}public String getName(){return "file";}public String getOriginalFilename(){return "roster.csv";}public String getContentType(){return "text/csv";}
        public boolean isEmpty(){try{return Files.size(path)==0;}catch(IOException e){throw new UncheckedIOException(e);}}public long getSize(){try{return Files.size(path);}catch(IOException e){throw new UncheckedIOException(e);}}
        public byte[] getBytes(){throw new UnsupportedOperationException("Streaming only");}public InputStream getInputStream()throws IOException{return Files.newInputStream(path);}public void transferTo(File file)throws IOException{Files.copy(path,file.toPath());}public void transferTo(Path file)throws IOException{Files.copy(path,file);}
    }
}
