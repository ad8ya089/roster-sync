package com.moveinsync.rostersync;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class HealthIT extends AbstractIntegrationTest {
    @LocalServerPort
    private int port;
    @org.springframework.boot.test.web.server.LocalManagementPort private int managementPort;

    @Test
    void readinessIncludesDatabaseAndLivenessIsPublic() {
        RestClient client = RestClient.create("http://localhost:" + managementPort);
        for (String probe : new String[]{"readiness", "liveness"}) {
            var response = client.get().uri("/actuator/health/" + probe).retrieve().toEntity(Map.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("status", "UP");
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        }
    }

    @Test
    void actuatorIsPublicOnlyOnTheManagementListener() {
        RestClient.create("http://localhost:" + managementPort).get().uri("/actuator/prometheus")
                .exchange((request,response)->{assertThat(response.getStatusCode().value()).isEqualTo(200);return null;});
        for(String path:new String[]{"/actuator/health/readiness","/actuator/metrics","/actuator/prometheus"})
        RestClient.create("http://localhost:" + port).get().uri(path)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(404);
                    assertThat(response.bodyTo(String.class)).contains("NOT_FOUND", "correlationId");
                    return null;
                });
    }
}
