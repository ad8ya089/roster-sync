package com.moveinsync.rostersync;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

public final class JwtTestTokens {
    public static final String SECRET = "test-only-secret-with-at-least-32-utf8-bytes";
    public static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private JwtTestTokens() { }

    public static String sign(String secret, Instant issuedAt, Instant expiresAt, Map<String, Object> overrides) {
        var claims = JwtClaimsSet.builder().subject("acme.admin").issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("tid", ACME.toString()).claim("tcode", "acme").claim("role", "TRANSPORT_ADMIN");
        claims.claims(values -> overrides.forEach((key, value) -> {
            if (value == null) values.remove(key); else values.put(key, value);
        }));
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
}
