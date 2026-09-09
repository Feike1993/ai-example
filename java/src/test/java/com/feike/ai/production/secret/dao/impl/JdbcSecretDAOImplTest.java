package com.feike.ai.production.secret.dao.impl;

import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;

import com.feike.ai.production.config.ProductionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@DisplayName("JdbcSecretDAOImpl")
class JdbcSecretDAOImplTest {

    @Test
    void missingKekShouldBeUnavailableAndFailClosed() {
        ProductionProperties properties = new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4, null, null,
            new ProductionProperties.Security("", "postgres", "v1", null, null),
            null, null, null
        );
        JdbcSecretDAOImpl store = new JdbcSecretDAOImpl(mock(JdbcTemplate.class), properties);
        assertFalse(store.available());
        assertThrows(SecretUnavailableException.class, () -> store.get(SecretResolver.JWT_HMAC));
    }
}
