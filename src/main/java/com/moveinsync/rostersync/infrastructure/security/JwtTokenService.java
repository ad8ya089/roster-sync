package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.config.RosterProperties;
import com.moveinsync.rostersync.domain.model.Role;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;
    private final Duration ttl;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtTokenService(RosterProperties properties, Clock clock) {
        this(properties.security(), clock);
    }

    // Package-visible constructor keeps the cryptographic tests independent of Spring and the DB.
    JwtTokenService(RosterProperties.Security settings, Clock clock) {
        byte[] secret = settings.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        var key = new SecretKeySpec(secret, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new JwtClaimsValidator(clock));
        this.clock = clock;
        ttl = settings.tokenTtl();
    }

    public String issue(CurrentUser user) {
        var issuedAt = clock.instant();
        var claims = JwtClaimsSet.builder().subject(user.username())
                .claim("tid", user.tenantId().toString()).claim("tcode", user.tenantCode())
                .claim("role", user.role().name()).issuedAt(issuedAt).expiresAt(issuedAt.plus(ttl));
        if (user.role() == Role.EMPLOYEE) claims.claim("emp", user.employeeId());
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    public JwtDecoder decoder() { return decoder; }
    public long expiresInSeconds() { return ttl.toSeconds(); }
}
