package com.moveinsync.rostersync.infrastructure.security;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.*;

class TenantContextFilterTest {
    private final TenantContextFilter filter = new TenantContextFilter();
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); MDC.clear(); }

    @Test void sequentialRequestsOnTheSameThreadNeverInheritTenantEvenAfterFailure() throws Exception {
        UUID acme = UUID.randomUUID();
        authenticate(acme);
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertThat(TenantContext.currentUser().tenantId()).isEqualTo(acme);
            assertThat(MDC.get("tenantId")).isEqualTo(acme.toString());
            throw new IllegalStateException("test failure");
        })).isInstanceOf(IllegalStateException.class);
        assertCleared();
        SecurityContextHolder.clearContext();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> assertCleared());
        UUID globex = UUID.randomUUID();
        authenticate(globex);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) ->
                assertThat(TenantContext.currentUser().tenantId()).isEqualTo(globex));
        assertCleared();
    }

    private static void assertCleared() {
        assertThatThrownBy(TenantContext::currentUser).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("tenantId")).isNull();
    }
    private static void authenticate(UUID tenantId) {
        var jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("user").issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(3600)).claim("tid", tenantId.toString())
                .claim("tcode", "test").claim("role", "OPERATOR").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
