package com.moveinsync.rostersync;

import com.moveinsync.rostersync.application.ops.OpsSummaryService;
import com.moveinsync.rostersync.domain.model.Role;
import com.moveinsync.rostersync.infrastructure.metrics.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
@TestPropertySource(properties="roster.metrics.refresh-interval=1h")
class MetricsIT extends AbstractReconciliationIntegrationTest {
    @Autowired MeterRegistry registry; @Autowired GaugeRefresher gauges;
    @Autowired OpsSummaryService summary; @Autowired RosterMetrics metrics; @Autowired Clock clock;
    @MockitoSpyBean JdbcClient client;
    @LocalManagementPort int managementPort;
    private double outcome(String outcome){return registry.find("roster.events").tag("outcome",outcome).counters().stream().mapToDouble(Counter::count).sum();}
    @Test void mixedFeedCountersAndDurableSummaryHaveDifferentScopes() throws Exception {
        double applied=outcome("applied"),stale=outcome("stale"),duplicates=outcome("duplicate");
        send(mapper.convertValue(mapper.readTree(Files.readString(Path.of("samples/feed.json"))).get("events"),List.class));
        assertThat(outcome("applied")-applied).isEqualTo(44);assertThat(outcome("stale")-stale).isEqualTo(3);assertThat(outcome("duplicate")-duplicates).isEqualTo(3);
        send(event("METRIC",1,"CREATE","BLR-1","S1"));send(event("METRIC",2,"UPDATE","BLR-1","S1"));
        send(event("METRIC",2,"UPDATE","BLR-1","S2"));var invalid=event("BAD",1,"CREATE","BLR-1","S1");invalid.put("phone","bad");send(invalid);
        var actual=summary.get(JwtTestTokens.ACME);
        assertThat(actual.durable().last15m()).isEqualTo(new OpsSummaryService.Outcomes(45,3,1,1,1));
        assertThat(actual.sinceRestart().instanceWide()).isTrue();assertThat(actual.sinceRestart().since()).isEqualTo(metrics.since());
        assertThat(actual.sinceRestart().p99ApplyMs()).isNotNull();assertThat(actual.sinceRestart().duplicates()).isGreaterThanOrEqualTo(3);
        UUID globex=UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertThat(summary.get(globex).durable().last15m().applied()).isZero();
        assertThat(summary.get(globex).sinceRestart()).isEqualTo(actual.sinceRestart());
        var freshRegistry=new SimpleMeterRegistry();try {
            var restartedMetrics=new RosterMetrics(freshRegistry,clock);
            var reconstructed=new OpsSummaryService(client,clock,restartedMetrics).get(JwtTestTokens.ACME);
            assertThat(reconstructed.durable().last15m()).isEqualTo(actual.durable().last15m());
            assertThat(reconstructed.sinceRestart().duplicates()).isZero();restartedMetrics.close();
        }finally{freshRegistry.close();}
        problem(request("GET","/api/v1/ops/summary",jwt(Role.TRANSPORT_ADMIN),null),403,"FORBIDDEN");
        assertThat(request("GET","/api/v1/ops/summary",jwt(Role.OPERATOR),null).statusCode()).isEqualTo(200);
    }
    @Test void brakeAndGaugesAreRefreshedBeforeScrapingWithoutDatabaseCalls() throws Exception {
        double before=registry.find("recon.brake.tripped").tag("reason","MISSING_RATIO").counters().stream().mapToDouble(Counter::count).sum();
        var run=finished(upload(header()+row("ONLY_NEW",1,"S1"),false));assertThat(run.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(registry.get("recon.brake.tripped").tag("reason","MISSING_RATIO").counter().count()-before).isEqualTo(1);
        assertThat(registry.find("recon.runs").counters()).allSatisfy(counter->assertThat(counter.getId().getTag("status")).isIn("PLANNED","AWAITING_APPROVAL","COMPLETED","REJECTED","FAILED"));
        send(event("GAUGE",1,"CREATE","BLR-1","S1"));gauges.refresh();
        assertThat(registry.get("outbox.pending").gauge().value()).isEqualTo(count("outbox_event"));
        assertThat(registry.get("recon.runs.awaiting.approval").gauge().value()).isEqualTo(1);
        clearInvocations(client);
        var scrape=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+managementPort+"/actuator/prometheus")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(200);assertThat(scrape.body()).contains("outbox_pending","recon_runs_awaiting_approval","cache_gets_total");
        verifyNoInteractions(client);
        assertThat(registry.getMeters()).allSatisfy(meter->assertThat(meter.getId().getTags()).noneMatch(tag->Set.of("tenantId","employeeId","tenant_id","employee_id").contains(tag.getKey())));
    }
}
