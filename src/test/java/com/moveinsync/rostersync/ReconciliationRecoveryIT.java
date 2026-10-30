package com.moveinsync.rostersync;
import com.moveinsync.rostersync.application.recon.*;
import com.moveinsync.rostersync.infrastructure.fault.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Timeout(60)
class ReconciliationRecoveryIT extends AbstractReconciliationIntegrationTest {
    @Autowired ReconciliationRecovery recovery;
    @ParameterizedTest @ValueSource(strings={"UPLOADED","DIFFING"}) void startupFailsOrphanedPlanningRuns(String status) throws Exception {
        UUID id=orphan(status);recovery.startup();assertThat(runs.get(JwtTestTokens.ACME,id).status()).isEqualTo("FAILED");
        assertThat(runs.metadata(JwtTestTokens.ACME,id).get("failure_reason")).isEqualTo("INTERRUPTED");
    }
    @Test void watchdogLeavesALiveFreshHeartbeatAlone() throws Exception {
        UUID id=orphan("DIFFING");recovery.watchdog();assertThat(runs.get(JwtTestTokens.ACME,id).status()).isEqualTo("DIFFING");
        jdbc.update("UPDATE recon_run SET heartbeat_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(180)),id);
        recovery.watchdog();assertThat(runs.get(JwtTestTokens.ACME,id).status()).isEqualTo("FAILED");
    }
    @Test void awaitingApprovalSurvivesStartup() throws Exception {
        UUID id=orphan("DIFFING");runs.transition(JwtTestTokens.ACME,id,"DIFFING","AWAITING_APPROVAL");recovery.startup();assertThat(runs.get(JwtTestTokens.ACME,id).status()).isEqualTo("AWAITING_APPROVAL");
    }
    @Test void restartedApplicationResumesPendingActionsWithoutDuplicatingCommittedOnes() throws Exception {
        UUID id;
        try(var first=open()) {
            var output=new ByteArrayOutputStream();first.getBean(DemoRosterExportService.class).write(JwtTestTokens.ACME,output,RosterFileGenerator.Spec.unchanged());
            String csv=output.toString(StandardCharsets.UTF_8);csv=String.join("\n",csv.lines().filter(line->!line.startsWith("#TRAILER") && !line.startsWith("\"#TRAILER\"")).toList())+"\n"+row("REC1",1,"S1")+row("REC2",1,"S1")+row("REC3",1,"S1");
            first.getBean(FaultInjector.class).arm(FaultPoint.RECON_AFTER_N_ACTIONS,FaultMode.THROW,true,0,3);
            id=first.getBean(ReconciliationUploadService.class).upload(JwtTestTokens.ACME,"fixture",new MockMultipartFile("file","roster.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)),Instant.now().plusSeconds(1),null,false).runId();
            UUID runId=id;await().atMost(Duration.ofSeconds(15)).until(()->runs.get(JwtTestTokens.ACME,runId).status().equals("APPLYING") && !first.getBean(ReconWorkQueue.class).active(runId));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_action WHERE status='APPLIED'",Long.class)).isEqualTo(2);
        }
        try(var restarted=open()) {
            assertThat(restarted.isActive()).isTrue();var result=finished(id);assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.applied()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE payload->>'source'='RECON'",Long.class)).isEqualTo(3);assertThat(count("hr_event_receipt")).isZero();
        }
    }
    private UUID orphan(String status) throws Exception {
        UUID id=UUID.randomUUID();Path path=temporary.resolve(id+".csv");Files.writeString(path,header());
        runs.create(JwtTestTokens.ACME,id,path,Files.size(path),Instant.now(),null,false,"fixture");
        if(status.equals("DIFFING")) runs.transition(JwtTestTokens.ACME,id,"UPLOADED","DIFFING");return id;
    }
    private ConfigurableApplicationContext open() {
        return new SpringApplicationBuilder(RosterSyncApplication.class).profiles("test","demo").run("--server.port=0","--spring.main.banner-mode=off",
                "--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=4","--roster.demo.password=test-demo-password","--roster.outbox.enabled=false","--roster.reconciliation.chunk-size=2",
                "--roster.reconciliation.data-dir="+Path.of("target/recon-restart-it").toAbsolutePath());
    }
}
