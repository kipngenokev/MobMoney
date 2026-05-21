package com.mobmoney.transfer.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RSA keypair used to sign (encoder) and verify (decoder) JWTs.
 *
 * Keys are read from configurable PEM resources. The bundled dev keys live on
 * the classpath; in production point {@code security.jwt.private-key} /
 * {@code public-key} at a mounted Kubernetes secret so all replicas share the
 * same keypair and tokens survive restarts.
 */
@Configuration
public class RsaKeyConfig {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public RsaKeyConfig(
            @Value("${security.jwt.public-key:classpath:keys/public.pem}") Resource publicKeyResource,
            @Value("${security.jwt.private-key:classpath:keys/private.pem}") Resource privateKeyResource) {
        this.publicKey = readPublicKey(publicKeyResource);
        this.privateKey = readPrivateKey(privateKeyResource);
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        JWK jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwks);
    }

    private RSAPublicKey readPublicKey(Resource resource) {
        try {
            String pem = stripPem(readAll(resource), "PUBLIC KEY");
            byte[] der = Base64.getDecoder().decode(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load JWT public key", e);
        }
    }

    private RSAPrivateKey readPrivateKey(Resource resource) {
        try {
            String pem = stripPem(readAll(resource), "PRIVATE KEY");
            byte[] der = Base64.getDecoder().decode(pem);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load JWT private key", e);
        }
    }

    private String readAll(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String stripPem(String pem, String type) {
        return pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
    }
}
