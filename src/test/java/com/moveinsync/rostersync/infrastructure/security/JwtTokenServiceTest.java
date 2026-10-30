package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.JwtTestTokens;
import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.model.Role;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.*;

import static org.assertj.core.api.Assertions.*;

class JwtTokenServiceTest {
    private final Instant now = Instant.parse("2026-10-30T12:00:00Z");
    private final JwtTokenService tokens = new JwtTokenService(
            new RosterProperties.Security(JwtTestTokens.SECRET, Duration.ofHours(1)), Clock.fixed(now, ZoneOffset.UTC));

    @ParameterizedTest @ValueSource(strings = {"HR_SYSTEM", "TRANSPORT_ADMIN", "OPERATOR", "EMPLOYEE"})
    void issuesSignedClaimsAndOnlyEmployeeCarriesEmployeeId(String roleName) {
        Role role = Role.valueOf(roleName);
        Jwt jwt = tokens.decoder().decode(tokens.issue(new CurrentUser("user", JwtTestTokens.ACME, "acme", role, "E1001")));
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getSubject()).isEqualTo("user");
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(JwtTestTokens.ACME.toString());
        assertThat(jwt.getClaimAsString("tcode")).isEqualTo("acme");
        assertThat(jwt.getClaimAsString("role")).isEqualTo(roleName);
        assertThat(jwt.getClaimAsString("emp")).isEqualTo(role == Role.EMPLOYEE ? "E1001" : null);
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(3600));
        assertThat(tokens.expiresInSeconds()).isEqualTo(3600);
    }

    @Test void expirationHasADistinctCodeWithAnExactThirtySecondSkew() {
        assertThatCode(() -> decode(now.minusSeconds(29), Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> decode(now.minusSeconds(30), Map.of())).isInstanceOfSatisfying(JwtValidationException.class,
                error -> assertThat(error.getErrors()).anyMatch(item -> item.getErrorCode().equals(JwtClaimsValidator.EXPIRED)));
    }

    @Test void rejectsInvalidSignatureAndWrongSecret() {
        String token = JwtTestTokens.sign(JwtTestTokens.SECRET, now, now.plusSeconds(3600), Map.of());
        var parts = token.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertThatThrownBy(() -> tokens.decoder().decode(String.join(".", parts))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.decoder().decode(JwtTestTokens.sign("different-test-key-with-at-least-32-bytes", now, now.plusSeconds(3600), Map.of())))
                .isInstanceOf(JwtException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"sub", "tid", "tcode", "role", "iat", "exp"})
    void rejectsMissingRequiredClaims(String missing) {
        Map<String, Object> values = new HashMap<>();
        values.put(missing, null);
        assertThatThrownBy(() -> decode(now.plusSeconds(3600), values)).isInstanceOf(JwtException.class);
    }

    @Test void rejectsUnknownRoleInvalidTenantAndEmployeeWithoutEmployeeId() {
        for (Map<String, Object> values : List.of(Map.<String, Object>of("role", "ADMIN"), Map.<String, Object>of("tid", "bad"),
                Map.<String, Object>of("role", "EMPLOYEE"), Map.<String, Object>of("role", "EMPLOYEE", "emp", " "))) {
            assertThatThrownBy(() -> decode(now.plusSeconds(3600), values)).isInstanceOf(JwtException.class);
        }
    }

    @Test void rejectsFutureIssuedAtAndNotBeforeOutsideSkew() {
        assertThatThrownBy(() -> decode(now.plusSeconds(3600), Map.of("iat", now.plusSeconds(31)))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decode(now.plusSeconds(3600), Map.of("nbf", now.plusSeconds(31)))).isInstanceOf(JwtException.class);
    }

    @Test void shortSecretFailsClearlyWithoutPrintingTheSecretAndCountsUtf8Bytes() {
        assertThatThrownBy(() -> new JwtTokenService(new RosterProperties.Security("canary-short-key", Duration.ofHours(1)), Clock.systemUTC()))
                .hasMessage("JWT_SECRET must contain at least 32 UTF-8 bytes");
        assertThatCode(() -> new JwtTokenService(new RosterProperties.Security("é".repeat(16), Duration.ofHours(1)), Clock.systemUTC()))
                .doesNotThrowAnyException();
    }

    private Jwt decode(Instant expires, Map<String, Object> overrides) {
        return tokens.decoder().decode(JwtTestTokens.sign(JwtTestTokens.SECRET, now.minusSeconds(3600), expires, overrides));
    }
}
