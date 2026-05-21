package com.mobmoney.transfer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Issues signed RS256 access tokens for authenticated users. */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long ttlSeconds;
    private final String issuer;

    public JwtService(JwtEncoder encoder,
                      @Value("${security.jwt.ttl-seconds:3600}") long ttlSeconds,
                      @Value("${security.jwt.issuer:mobmoney-transfer-service}") String issuer) {
        this.encoder = encoder;
        this.ttlSeconds = ttlSeconds;
        this.issuer = issuer;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String issueToken(String username, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttlSeconds, ChronoUnit.SECONDS))
                .subject(username)
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(() -> "RS256").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
