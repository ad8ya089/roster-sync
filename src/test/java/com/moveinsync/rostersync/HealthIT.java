package com.moveinsync.rostersync;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class HealthIT extends AbstractIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    void readinessIncludesDatabaseAndLivenessIsPublic() {
        RestClient client = RestClient.create("http://localhost:" + port);
        for (String probe : new String[]{"readiness", "liveness"}) {
            var response = client.get().uri("/actuator/health/" + probe).retrieve().toEntity(Map.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "UP");
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        }
    }

    @Test
    void managementMetricsFailClosedBeforeJwtIsImplemented() {
        RestClient.create("http://localhost:" + port).get().uri("/actuator/metrics")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(401);
                    assertThat(response.bodyTo(String.class)).contains("UNAUTHENTICATED", "correlationId");
                    return null;
                });
    }
}
