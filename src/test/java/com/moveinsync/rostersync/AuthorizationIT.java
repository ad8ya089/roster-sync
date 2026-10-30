package com.moveinsync.rostersync;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.*;

class AuthorizationIT extends AbstractSecurityIntegrationTest {
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
    @Autowired private org.springframework.security.web.FilterChainProxy security;

    @Test void tenantFilterRunsAfterBearerAndCorrelationRunsOutsideSecurity() {
        var filters = security.getFilterChains().getFirst().getFilters().stream().map(filter -> filter.getClass().getSimpleName()).toList();
        assertThat(filters.indexOf("TenantContextFilter")).isGreaterThan(filters.indexOf("BearerTokenAuthenticationFilter"));
        assertThat(filters).doesNotContain("CorrelationIdFilter", "LogoutFilter", "OAuth2ProtectedResourceMetadataFilter");
        assertThat(com.moveinsync.rostersync.api.error.CorrelationIdFilter.class
                .getAnnotation(org.springframework.core.annotation.Order.class).value()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        System.out.println("Verified security filter order: " + String.join(" -> ", filters));
    }

    @Test void frameworkDefaultsDoNotExposeExtraAnonymousRoutes() throws Exception {
        for (String path : List.of("/logout", "/login")) {
            problem(request(path.equals("/logout") ? "POST" : "GET", path, null, null), 404, "NOT_FOUND");
        }
        for (String path : List.of("/.well-known/oauth-protected-resource", "/api/v1/not-a-route")) {
            problem(request(path.equals("/logout") ? "POST" : "GET", path, null, null), 401, "UNAUTHENTICATED");
        }
    }

    static Stream<Arguments> roleMatrix() {
        return Stream.of("NONE", "HR_SYSTEM", "TRANSPORT_ADMIN", "OPERATOR", "EMPLOYEE").flatMap(role ->
                Stream.of("LIST", "GET", "CREATE", "PATCH").map(endpoint -> Arguments.of(role, endpoint)));
    }

    @ParameterizedTest(name = "{0} -> {1}") @MethodSource("roleMatrix")
    void enforcesRoleBeforeTenantResourceLookup(String role, String endpoint) throws Exception {
        String jwt = role.equals("NONE") ? null : token(switch (role) {
            case "HR_SYSTEM" -> "acme.hr";
            case "TRANSPORT_ADMIN" -> "acme.admin";
            case "OPERATOR" -> "acme.operator";
            default -> "E1001";
        });
        boolean allowed = role.equals("TRANSPORT_ADMIN") || (role.equals("OPERATOR") && Set.of("LIST", "GET").contains(endpoint));
        String method = endpoint.equals("CREATE") ? "POST" : endpoint.equals("PATCH") ? "PATCH" : "GET";
        String path = "/api/v1/offices" + (endpoint.equals("GET") || endpoint.equals("PATCH") ? "/BLR-1" : "");
        String body = endpoint.equals("CREATE") ? "{\"code\":\"NEW-1\",\"name\":\"Office\"}"
                : endpoint.equals("PATCH") ? "{\"active\":false}" : null;
        var response = request(method, path, jwt, body);
        if (role.equals("NONE")) problem(response, 401, "UNAUTHENTICATED");
        else if (!allowed) {
            problem(response, 403, "FORBIDDEN");
            if (endpoint.equals("GET") || endpoint.equals("PATCH")) {
                problem(request(method, "/api/v1/offices/MISSING", jwt, body), 403, "FORBIDDEN");
            }
        } else assertThat(response.statusCode()).as(response.body()).isEqualTo(endpoint.equals("CREATE") ? 201 : 200);
    }

    @ParameterizedTest @ValueSource(strings = {"EXPIRED", "TAMPERED", "WRONG_SECRET", "MISSING_EMP", "UNKNOWN_ROLE", "MISSING_TENANT"})
    void invalidTokensFailForEveryOfficeEndpoint(String kind) throws Exception {
        Instant now = Instant.now();
        var claims = new HashMap<String, Object>();
        if (kind.equals("MISSING_EMP")) claims.put("role", "EMPLOYEE");
        if (kind.equals("UNKNOWN_ROLE")) claims.put("role", "SUPER_ADMIN");
        if (kind.equals("MISSING_TENANT")) claims.put("tid", null);
        String jwt = JwtTestTokens.sign(kind.equals("WRONG_SECRET") ? "another-secret-with-more-than-thirty-two-bytes" : JwtTestTokens.SECRET,
                now.minusSeconds(7200), kind.equals("EXPIRED") ? now.minusSeconds(31) : now.plusSeconds(3600), claims);
        if (kind.equals("TAMPERED")) {
            String[] parts = jwt.split("\\.");
            parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
            jwt = String.join(".", parts);
        }
        for (var endpoint : List.of(new String[]{"GET", "/api/v1/offices", null}, new String[]{"GET", "/api/v1/offices/BLR-1", null},
                new String[]{"POST", "/api/v1/offices", "{\"code\":\"NEW-1\",\"name\":\"Office\"}"},
                new String[]{"PATCH", "/api/v1/offices/BLR-1", "{\"active\":false}"})) {
            problem(request(endpoint[0], endpoint[1], jwt, endpoint[2]), 401, kind.equals("EXPIRED") ? "TOKEN_EXPIRED" : "UNAUTHENTICATED");
        }
    }

    @Test void everyNonPublicRegisteredEndpointRequiresAToken() throws Exception {
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var mapping = entry.getKey();
            for (String pattern : mapping.getPatternValues()) {
                if (pattern.equals("/api/v1/auth/token") || pattern.startsWith("/actuator/health")) continue;
                if (pattern.startsWith("/api/v1/")) assertThat(entry.getValue().getMethod()
                        .getDeclaredAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)).isNotNull();
                String path = pattern.replaceAll("\\{[^}]+}", "MISSING");
                var methods = mapping.getMethodsCondition().getMethods();
                for (String method : methods.isEmpty() ? List.of("GET", "POST") : methods.stream().map(Enum::name).toList()) {
                    problem(request(method, path, null, null), 401, "UNAUTHENTICATED");
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(4);
    }

    @Test void disabledAndUnknownUsersAndWrongPasswordsAreIndistinguishable() throws Exception {
        jdbc.update("UPDATE app_user SET enabled=false WHERE tenant_id=? AND username='acme.admin'", JwtTestTokens.ACME);
        for (Map<String, String> credentials : List.of(Map.of("username", "absent", "password", "wrong"),
                Map.of("username", "acme.hr", "password", "wrong"), Map.of("username", "acme.admin", "password", "test-demo-password"))) {
            problem(request("POST", "/api/v1/auth/token", null, mapper.writeValueAsString(credentials)), 401, "UNAUTHENTICATED");
        }
    }

    @Test void disablingAUserDoesNotRevokeAnAlreadyIssuedStatelessToken() throws Exception {
        String jwt = token("acme.admin");
        jdbc.update("UPDATE app_user SET enabled=false WHERE tenant_id=? AND username='acme.admin'", JwtTestTokens.ACME);
        assertThat(request("GET", "/api/v1/offices", jwt, null).statusCode()).isEqualTo(200);
    }

    @Test void employeeWithoutExternalIdCannotLogin() throws Exception {
        jdbc.update("UPDATE app_user SET employee_external_id=null WHERE tenant_id=? AND username='E1001'", JwtTestTokens.ACME);
        problem(request("POST", "/api/v1/auth/token", null, "{\"username\":\"E1001\",\"password\":\"test-demo-password\"}"), 401, "UNAUTHENTICATED");
    }
}
