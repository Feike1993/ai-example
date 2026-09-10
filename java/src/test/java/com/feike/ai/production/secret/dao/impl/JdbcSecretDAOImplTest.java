package com.feike.ai.production.secret.dao.impl;

import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.secret.manager.EnvelopeCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JdbcSecretDAOImpl")
class JdbcSecretDAOImplTest {

    @Test
    void missingKekShouldBeUnavailableAndFailClosed() {
        ProductionProperties properties = new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4, null, null,
            new ProductionProperties.Security("", "postgres", "v1", null, null, null),
            null, null, null, null
        );
        JdbcSecretDAOImpl store = new JdbcSecretDAOImpl(mock(JdbcTemplate.class), properties);
        assertFalse(store.available());
        assertThrows(SecretUnavailableException.class, () -> store.get(SecretResolver.JWT_HMAC));
    }

    @Test
    void previousKekShouldDecryptOldCiphertext() {
        String oldKek = EnvelopeCrypto.randomKeyBase64();
        String newKek = EnvelopeCrypto.randomKeyBase64();
        EnvelopeCrypto.EncryptedBlob blob = EnvelopeCrypto.encryptString(
            EnvelopeCrypto.parseKek(oldKek), "v1", "sk-secret");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(SecretResolver.JWT_HMAC))).thenReturn(List.of(Map.of(
            "ciphertext", blob.ciphertext(),
            "nonce", blob.nonce(),
            "kek_id", "v1"
        )));
        ProductionProperties properties = new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4, null, null,
            new ProductionProperties.Security(newKek, "postgres", "v2", null, null, oldKek),
            null, null, null, null
        );
        JdbcSecretDAOImpl store = new JdbcSecretDAOImpl(jdbc, properties);
        assertEquals("sk-secret", store.get(SecretResolver.JWT_HMAC).orElseThrow());
    }
}
