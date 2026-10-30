package com.moveinsync.rostersync;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ErrorHandlingIT extends AbstractSecurityIntegrationTest {
    private static final String CANARY = "Zzcanary-person-at-pii-test";

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/offices", "{\"name\":\"" + CANARY + "\",", "application/json", 400, "MALFORMED_JSON"),
                Arguments.of("POST", "/api/v1/offices", "{\"code\":\"" + CANARY + "\",\"name\":\"Office\"}", "application/json", 400, "VALIDATION_FAILED"),
                Arguments.of("POST", "/api/v1/offices", "{\"code\":\"NEW-1\",\"name\":\"" + CANARY.repeat(9) + "\"}", "application/json", 400, "VALIDATION_FAILED"),
                Arguments.of("PATCH", "/api/v1/offices/BLR-1", "{\"active\":\"" + CANARY + "\"}", "application/json", 400, "MALFORMED_JSON"),
                Arguments.of("GET", "/api/v1/offices?includeInactive=" + CANARY, null, "application/json", 400, "VALIDATION_FAILED"),
                Arguments.of("GET", "/api/v1/test-errors/numbers/" + CANARY, null, "application/json", 400, "VALIDATION_FAILED"),
                Arguments.of("GET", "/api/v1/unknown/" + CANARY, null, "application/json", 404, "NOT_FOUND"),
                Arguments.of("DELETE", "/api/v1/offices/" + CANARY, null, "application/json", 405, "METHOD_NOT_ALLOWED"),
                Arguments.of("POST", "/api/v1/offices", CANARY, "text/plain", 415, "UNSUPPORTED_MEDIA_TYPE"),
                Arguments.of("GET", "/api/v1/test-errors/failure?input=" + CANARY, null, "application/json", 500, "INTERNAL_ERROR"),
                Arguments.of("GET", "/api/v1/test-errors/dependency?input=" + CANARY, null, "application/json", 503, "DEPENDENCY_UNAVAILABLE"),
                Arguments.of("GET", "/api/v1/test-errors/upload?input=" + CANARY, null, "application/json", 413, "PAYLOAD_TOO_LARGE"));
    }

    @ParameterizedTest @MethodSource("invalidRequests")
    void errorsNeverEchoCanariesOrImplementationDetails(String method, String path, String body, String mediaType,
                                                       int status, String code, CapturedOutput output) throws Exception {
        String jwt = token(path.contains("test-errors") ? "acme.operator" : "acme.admin");
        var response = request(method, path, jwt, body, mediaType);
        problem(response, status, code);
        assertThat(response.body()).doesNotContain(CANARY, "Exception", "org.", "java.", "SELECT", "INSERT", "constraint", "stackTrace");
        assertThat(output.getAll()).doesNotContain(CANARY);
        var errors = mapper.readTree(response.body()).get("errors");
        if (errors != null) for (var error : errors) {
            assertThat(error.size()).isEqualTo(2);
            assertThat(error.has("field") && error.has("code")).isTrue();
        }
    }

    @Test void realUniqueConstraintIsMappedWithoutLeakingItsNameOrRejectedValue(CapturedOutput output) throws Exception {
        String jwt = token("acme.admin");
        String code = "CANARY-OFFICE-437";
        String body = "{\"code\":\"" + code + "\",\"name\":\"" + CANARY + "\"}";
        assertThat(request("POST", "/api/v1/offices", jwt, body).statusCode()).isEqualTo(201);
        var response = request("POST", "/api/v1/offices", jwt, body);
        problem(response, 409, "OFFICE_CODE_EXISTS");
        assertThat(response.body()).doesNotContain(code, CANARY, "office_tenant_id_code_key", "SQL", "Exception", "constraint");
        assertThat(output.getAll()).doesNotContain(CANARY);
    }

    @Test void authenticationAndAuthorizationErrorsHaveCorrelationAndNoCredentialValues() throws Exception {
        var unauthorized = request("POST", "/api/v1/auth/token", null,
                "{\"username\":\"" + CANARY + "\",\"password\":\"" + CANARY + "\"}");
        problem(unauthorized, 401, "UNAUTHENTICATED");
        assertThat(unauthorized.body()).doesNotContain(CANARY);
        problem(request("GET", "/api/v1/offices", token("acme.hr"), null), 403, "FORBIDDEN");
    }
}
