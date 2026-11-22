package pl.edu.pjasensei.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Stands in for the identity provider at the network level: it serves real signing keys over HTTP
 * and signs real tokens, so Spring's production token validation is what gets exercised.
 */
public final class TestIdentityProvider {

    /** Never fetched: nothing listens here, which proves the keys come from their own address. */
    static final String ISSUER = "https://issuer.invalid/pool";

    private final RSAKey signingKey = newKey();
    private final String jwkSetUri;

    TestIdentityProvider() {
        byte[] jwkSet = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwkSet.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(jwkSet);
                }
            });
            server.start();
            jwkSetUri = "http://localhost:%d/jwks.json".formatted(server.getAddress().getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String jwkSetUri() {
        return jwkSetUri;
    }

    /** A valid token for a new user who has no row in the database. */
    public String token() {
        return token(claims -> {});
    }

    /** A valid token for a new user, with the claims a test wants to change. */
    public String token(Consumer<JWTClaimsSet.Builder> changes) {
        return sign(claims(changes), signingKey);
    }

    /** Otherwise valid, but signed with a key this provider does not publish. */
    public String tokenSignedWithAnotherKey() {
        return sign(claims(changes -> {}), newKey());
    }

    private static JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> changes) {
        var now = Instant.now();
        var subject = UUID.randomUUID().toString();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("username", "user-" + subject)
                .claim("token_use", "access");
        changes.accept(claims);
        return claims.build();
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build();
        var token = new SignedJWT(header, claims);
        try {
            token.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return token.serialize();
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
