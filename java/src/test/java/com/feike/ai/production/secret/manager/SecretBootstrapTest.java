package com.feike.ai.production.secret.manager;

import com.feike.ai.production.secret.dao.SecretResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SecretBootstrap")
class SecretBootstrapTest {

    @Test
    void existingRowsMustNotBeOverwritten() {
        EnvSecretResolver secrets = new EnvSecretResolver();
        secrets.put(SecretResolver.JWT_HMAC, "keep-hmac");
        secrets.put(SecretResolver.llmKey("deepseek"), "keep-key");

        new SecretBootstrap(secrets).run(new DefaultApplicationArguments());

        assertEquals("keep-hmac", secrets.require(SecretResolver.JWT_HMAC));
        assertEquals("keep-key", secrets.require(SecretResolver.llmKey("deepseek")));
    }

    @Test
    void emptyStoreShouldSeedJwtHmacOnce() {
        EnvSecretResolver secrets = new EnvSecretResolver();
        new SecretBootstrap(secrets).run(new DefaultApplicationArguments());
        assertTrue(secrets.contains(SecretResolver.JWT_HMAC));
        String first = secrets.require(SecretResolver.JWT_HMAC);
        new SecretBootstrap(secrets).run(new DefaultApplicationArguments());
        assertEquals(first, secrets.require(SecretResolver.JWT_HMAC));
    }
}
