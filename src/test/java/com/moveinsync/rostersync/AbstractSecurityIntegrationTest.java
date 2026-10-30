package com.moveinsync.rostersync;

import com.moveinsync.rostersync.infrastructure.persistence.DemoDataSeeder;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("demo")
@TestPropertySource(properties = "roster.demo.password=test-demo-password")
public abstract class AbstractSecurityIntegrationTest extends AbstractIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort protected int port;
    @Autowired protected JsonMapper mapper;
    @Autowired private DemoDataSeeder seeder;

    @BeforeEach void seedUsersAndOffices() { seeder.run(new DefaultApplicationArguments()); }

    protected String token(String username) throws Exception {
        var response = request("POST", "/api/v1/auth/token", null,
                mapper.writeValueAsString(Map.of("username", username, "password", "test-demo-password")));
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body()).get("accessToken").asText();
    }

    protected HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        return request(method, path, token, body, "application/json");
    }

    protected HttpResponse<String> request(String method, String path, String token, String body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", contentType);
        return CLIENT.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    protected void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var json = mapper.readTree(response.body());
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("correlationId").asText()).isEqualTo(response.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }
}
