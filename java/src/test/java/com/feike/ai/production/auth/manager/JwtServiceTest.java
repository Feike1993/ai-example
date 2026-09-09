package com.feike.ai.production.auth.manager;

import com.feike.ai.production.auth.model.ProductionPrincipal;

import com.feike.ai.production.secret.manager.EnvSecretResolver;
import com.feike.ai.production.secret.manager.EnvelopeCrypto;
import com.feike.ai.production.secret.dao.SecretResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("JwtService")
class JwtServiceTest {

    @Test
    void issueAndParseShouldRoundTripClaims() {
        EnvSecretResolver secrets = new EnvSecretResolver();
        secrets.put(SecretResolver.JWT_HMAC, EnvelopeCrypto.randomKeyBase64());
        JwtService jwt = new JwtService(secrets, Duration.ofHours(1));
        ProductionPrincipal original = new ProductionPrincipal("alice", "tenant-a", Set.of("USER", "ADMIN"));
        ProductionPrincipal parsed = jwt.parse(jwt.issue(original));
        assertEquals("alice", parsed.subject());
        assertEquals("tenant-a", parsed.tenantId());
        assertEquals(Set.of("USER", "ADMIN"), parsed.roles());
    }
}
