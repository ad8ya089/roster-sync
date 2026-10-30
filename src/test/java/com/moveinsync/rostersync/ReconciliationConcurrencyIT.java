package com.moveinsync.rostersync;
import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.application.recon.*;
import com.moveinsync.rostersync.domain.model.Role;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class ReconciliationConcurrencyIT extends AbstractReconciliationIntegrationTest {
    @Autowired ReconciliationChunkApplier chunks;
    @Test void twoUploadsAcceptExactlyOneActiveRun() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> task=()->{gate.await();try{upload(header()+row("NEW",1,"S1"),false);return 202;}catch(ConflictException conflict){assertThat(conflict.errorCode()).isEqualTo(ErrorCode.RECON_RUN_ALREADY_ACTIVE);return 409;}};
            var one=pool.submit(task);var two=pool.submit(task);gate.countDown();assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,409);
        }
        assertThat(count("recon_run")).isEqualTo(1);
        finished(jdbc.queryForObject("SELECT id FROM recon_run",UUID.class));
    }
    @Test void concurrentApproveReturnsOneAcceptedAndOneConflict() throws Exception {
        var run=finished(upload(header()+row("NEW",1,"S1"),false));var gate=new CountDownLatch(1);String token=jwt(Role.OPERATOR);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> task=()->{gate.await();return request("POST","/api/v1/reconciliation/runs/"+run.runId()+"/approve",token,"{\"mode\":\"ALL\"}").statusCode();};
            var one=pool.submit(task);var two=pool.submit(task);gate.countDown();assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,409);
        }
        assertThat(finished(run.runId()).status()).isEqualTo("COMPLETED");
    }
    @Test void eventAfterPlanningSupersedesTheReconciliationAction() throws Exception {
        removeFixtureEmployees();send(event("RACE",1,"CREATE","BLR-1","S1"));
        // Expected count deliberately brakes without applying so a real-time update can overtake.
        var run=finished(upload(header()+row("RACE",3,"S2"),Instant.now().plusSeconds(1),false,2));
        assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");send(event("RACE",2,"UPDATE","BLR-1","S3"));
        decisions.decide(JwtTestTokens.ACME,run.runId(),true,"ALL","fixture",null);
        var result=finished(run.runId());assertThat(result.superseded()).isEqualTo(1);assertThat(version("RACE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT shift_code FROM employee WHERE external_id='RACE'",String.class)).isEqualTo("S3");
    }
    @Test void crossTenantAndWrongRoleCannotReadOrDecideRuns() throws Exception {
        var run=finished(upload(header()+row("NEW",1,"S1"),false));
        assertThat(request("GET","/api/v1/reconciliation/runs/"+run.runId(),token("globex.operator"),null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/api/v1/reconciliation/runs/"+run.runId()+"/approve",token("globex.operator"),"{\"mode\":\"ALL\"}").statusCode()).isEqualTo(404);
        assertThat(request("GET","/api/v1/reconciliation/runs/"+run.runId(),jwt(Role.EMPLOYEE),null).statusCode()).isEqualTo(403);
        assertThat(request("GET","/api/v1/settings/reconciliation",jwt(Role.HR_SYSTEM),null).statusCode()).isEqualTo(403);
        assertThat(request("POST","/api/v1/dev/seed-employees?count=1",jwt(Role.TRANSPORT_ADMIN),null).statusCode()).isEqualTo(403);
    }
    @Test void failedChunkRollsBackBeforePerActionFallback() throws Exception {
        removeFixtureEmployees();send(event("A",1,"CREATE","BLR-1","S1"));send(event("B",1,"CREATE","BLR-2","S1"));
        String csv=header()+row("A",2,"S2")+row("B",2,"S2").replace(",BLR-1,",",BLR-2,");
        var run=finished(upload(csv,Instant.now().plusSeconds(1),false,3));
        jdbc.update("UPDATE office SET active=false WHERE tenant_id=? AND code='BLR-1'",JwtTestTokens.ACME);
        decisions.decide(JwtTestTokens.ACME,run.runId(),true,"ALL","fixture",null);
        var result=finished(run.runId());assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.applied()).isEqualTo(1);
        assertThat(version("A")).isEqualTo(1);assertThat(version("B")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE status='FAILED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE payload->>'source'='RECON'",Long.class)).isEqualTo(1);
        assertThat(count("hr_event_receipt")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT error_codes FROM record_rejection WHERE source='FILE' AND external_id='A'",String.class)).contains("INACTIVE_OFFICE");
    }
}
