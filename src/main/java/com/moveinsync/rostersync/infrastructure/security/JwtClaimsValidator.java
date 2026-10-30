package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.domain.model.Role;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;

final class JwtClaimsValidator implements OAuth2TokenValidator<Jwt> {
    static final String EXPIRED = "roster_token_expired";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private final Clock clock;

    JwtClaimsValidator(Clock clock) { this.clock = clock; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            var now = clock.instant();
            if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null) return invalid();
            // A distinct machine-readable code survives Spring's authentication exception wrappers.
            if (!now.minus(CLOCK_SKEW).isBefore(jwt.getExpiresAt())) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED, "Token expired", null));
            }
            if (jwt.getIssuedAt().isAfter(now.plus(CLOCK_SKEW))
                    || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now.plus(CLOCK_SKEW)))) return invalid();
            Role role = Role.valueOf(jwt.getClaimAsString("role"));
            UUID tenantId = UUID.fromString(jwt.getClaimAsString("tid"));
            new CurrentUser(jwt.getSubject(), tenantId, jwt.getClaimAsString("tcode"), role, jwt.getClaimAsString("emp"));
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return invalid();
        }
    }

    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims", null));
    }
}
