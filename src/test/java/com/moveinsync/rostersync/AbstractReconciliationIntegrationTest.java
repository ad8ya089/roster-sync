package com.moveinsync.rostersync;
import com.moveinsync.rostersync.application.recon.*;
import com.moveinsync.rostersync.domain.model.Role;
import com.moveinsync.rostersync.infrastructure.persistence.ReconRunDao;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties="roster.reconciliation.data-dir=target/recon-it")
public abstract class AbstractReconciliationIntegrationTest extends AbstractIngestionIntegrationTest {
    @org.junit.jupiter.api.AfterEach void awaitWorkerCleanup(){await().dontCatchUncaughtExceptions().atMost(Duration.ofSeconds(90)).until(queue::idle);}
    @Autowired protected ReconciliationUploadService uploads;
    @Autowired protected ReconciliationQueryService queries;
    @Autowired protected ReconciliationDecisionService decisions;
    @Autowired protected ReconciliationRunner runner;
    @Autowired protected ReconRunDao runs;
    @Autowired protected DemoRosterSeeder seed;
    @Autowired protected DemoRosterExportService exporter;
    @Autowired protected SettingsService settings;
    @Autowired protected ReconciliationApplier applier;
    @Autowired protected ReconWorkQueue queue;
    @TempDir protected Path temporary;
    protected String header(){return String.join(",",com.moveinsync.rostersync.infrastructure.csv.RosterCsvReader.COLUMNS)+"\n";}
    protected String row(String id,long version,String shift){return id+","+version+",Fixture Person,fixture@example.test,+919800000001,,BLR-1,"+shift+",ACTIVE\n";}
    protected UUID upload(String csv,Instant asOf,boolean dryRun,Integer expected) throws IOException {
        return uploads.upload(JwtTestTokens.ACME,"fixture",new MockMultipartFile("file","roster.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)),asOf,expected,dryRun).runId();
    }
    protected UUID upload(String csv,boolean dryRun) throws IOException {return upload(csv,Instant.now().plusSeconds(1),dryRun,null);}
    protected ReconciliationQueryService.RunView finished(UUID id) {
        return finished(id,Duration.ofSeconds(60));
    }
    protected ReconciliationQueryService.RunView finished(UUID id,Duration maximum) {
        await().atMost(maximum).until(()->Set.of("PLANNED","AWAITING_APPROVAL","COMPLETED","FAILED","REJECTED").contains(runs.get(JwtTestTokens.ACME,id).status()) && !queue.active(id));
        return queries.get(JwtTestTokens.ACME,id,Role.OPERATOR,"fixture");
    }
    protected String export(RosterFileGenerator.Spec spec) throws IOException {var output=new ByteArrayOutputStream();exporter.write(JwtTestTokens.ACME,output,spec);return output.toString(StandardCharsets.UTF_8);}
    protected HttpResponse<String> multipart(String csv,String asOf,String jwt) throws Exception {
        String boundary="recon-boundary-"+UUID.randomUUID();
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"asOf\"\r\n\r\n"+asOf+"\r\n--"+boundary+
                "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"roster.csv\"\r\nContent-Type: text/csv\r\n\r\n"+csv+"\r\n--"+boundary+"--\r\n";
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/reconciliation/runs"))
                .header("Authorization","Bearer "+jwt).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build(),HttpResponse.BodyHandlers.ofString());
    }
    protected void removeFixtureEmployees(){jdbc.update("DELETE FROM route_seat");jdbc.update("DELETE FROM cab_booking");jdbc.update("DELETE FROM employee WHERE tenant_id=?",JwtTestTokens.ACME);}
}
