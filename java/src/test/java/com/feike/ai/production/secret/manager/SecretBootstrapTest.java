package com.feike.ai.production.secret.manager;

import com.feike.ai.production.secret.dao.SecretResolver;

import com.feike.ai.core.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SecretBootstrap")
class SecretBootstrapTest {

    @Test
    void existingRowMustNotBeOverwrittenByEnv() {
        EnvSecretResolver secrets = new EnvSecretResolver();
        secrets.put(SecretResolver.JWT_HMAC, "keep-hmac");
        secrets.put(SecretResolver.llmKey("deepseek"), "keep-key");

        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("deepseek", new AiProperties.Provider(
            "DeepSeek", "https://api.deepseek.com", "sk-from-env", "deepseek-v4-flash",
            null, null, null
        ));
        AiProperties ai = new AiProperties(
            "deepseek", 0.2, providers, null, null, null, null, null, null, null, null, null, null);

        new SecretBootstrap(secrets, ai).run(new DefaultApplicationArguments());

        assertEquals("keep-hmac", secrets.require(SecretResolver.JWT_HMAC));
        assertEquals("keep-key", secrets.require(SecretResolver.llmKey("deepseek")));
    }

    @Test
    void emptyStoreShouldSeedJwtHmacOnce() {
        EnvSecretResolver secrets = new EnvSecretResolver();
        AiProperties ai = new AiProperties(
            null, null, null, null, null, null, null, null, null, null, null, null, null);
        new SecretBootstrap(secrets, ai).run(new DefaultApplicationArguments());
        assertTrue(secrets.contains(SecretResolver.JWT_HMAC));
        String first = secrets.require(SecretResolver.JWT_HMAC);
        new SecretBootstrap(secrets, ai).run(new DefaultApplicationArguments());
        assertEquals(first, secrets.require(SecretResolver.JWT_HMAC));
    }
}
