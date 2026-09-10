package com.feike.ai.production.secret.dao.impl;

import com.feike.ai.production.secret.manager.EnvelopeCrypto;

import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;

import com.feike.ai.production.config.ProductionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从 {@code prod_secret} 读写密文，用 {@code PRODUCTION_KEK} 解。
 * <p>
 * 轮换窗口内可同时持有 {@code PRODUCTION_KEK_PREVIOUS}：当前钥匙解不开时再试上一把。
 * ADMIN {@code POST /api/v1/secrets/rotate} 会按当前 kek-id 重加密全部行。
 * KEK 解析失败时 {@link #available()} 为 false：此时 get/put 抛 503 语义异常，
 * 应用其余部分（教学样例）不受影响。
 */
public class JdbcSecretDAOImpl implements SecretResolver {

    private static final Logger log = LoggerFactory.getLogger(JdbcSecretDAOImpl.class);

    private final JdbcTemplate jdbc;
    private final ProductionProperties properties;
    private volatile SecretKey kek;
    private volatile SecretKey previousKek;
    private volatile String kekError;

    /**
     * @param jdbc       数据源
     * @param properties 读取 KEK 与 kekId
     */
    public JdbcSecretDAOImpl(JdbcTemplate jdbc, ProductionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        try {
            this.kek = EnvelopeCrypto.parseKek(properties.security().kek());
        } catch (SecretUnavailableException ex) {
            this.kekError = ex.getMessage();
            log.warn("工业级信封密钥不可用，/api/v1 将返回 503: {}", ex.getMessage());
        }
        String previous = properties.security().kekPrevious();
        if (previous != null && !previous.isBlank()) {
            try {
                this.previousKek = EnvelopeCrypto.parseKek(previous);
            } catch (SecretUnavailableException ex) {
                log.warn("PRODUCTION_KEK_PREVIOUS 无法解析，轮换窗口内旧密文可能解不开: {}", ex.getMessage());
            }
        }
    }

    @Override
    public boolean available() {
        return kek != null;
    }

    @Override
    public Optional<String> get(String name) {
        ensureKek();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT ciphertext, nonce, kek_id FROM prod_secret WHERE name = ?",
            name
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        EnvelopeCrypto.EncryptedBlob blob = new EnvelopeCrypto.EncryptedBlob(
            (byte[]) row.get("ciphertext"),
            (byte[]) row.get("nonce"),
            String.valueOf(row.get("kek_id"))
        );
        return Optional.of(decryptWithFallback(blob));
    }

    @Override
    public void put(String name, String plaintext) {
        ensureKek();
        EnvelopeCrypto.EncryptedBlob blob = EnvelopeCrypto.encryptString(
            kek,
            properties.security().kekId(),
            plaintext
        );
        jdbc.update(
            """
                INSERT INTO prod_secret (name, ciphertext, nonce, kek_id, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (name) DO UPDATE
                   SET ciphertext = EXCLUDED.ciphertext,
                       nonce = EXCLUDED.nonce,
                       kek_id = EXCLUDED.kek_id,
                       updated_at = NOW()
                """,
            name,
            blob.ciphertext(),
            blob.nonce(),
            blob.kekId()
        );
    }

    @Override
    public boolean contains(String name) {
        ensureKek();
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM prod_secret WHERE name = ?",
            Integer.class,
            name
        );
        return count != null && count > 0;
    }

    /**
     * 用当前 KEK 重加密所有行，kek_id 写成配置中的新版本。
     *
     * @return 重写行数
     */
    @Override
    public int reencryptAll() {
        ensureKek();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT name, ciphertext, nonce, kek_id FROM prod_secret"
        );
        int rewritten = 0;
        for (Map<String, Object> row : rows) {
            EnvelopeCrypto.EncryptedBlob blob = new EnvelopeCrypto.EncryptedBlob(
                (byte[]) row.get("ciphertext"),
                (byte[]) row.get("nonce"),
                String.valueOf(row.get("kek_id"))
            );
            String plaintext = decryptWithFallback(blob);
            put(String.valueOf(row.get("name")), plaintext);
            rewritten++;
        }
        log.info("KEK 重加密完成: rows={}, kekId={}", rewritten, properties.security().kekId());
        return rewritten;
    }

    private String decryptWithFallback(EnvelopeCrypto.EncryptedBlob blob) {
        try {
            return EnvelopeCrypto.decryptString(kek, blob);
        } catch (SecretUnavailableException ex) {
            if (previousKek == null) {
                throw ex;
            }
            return EnvelopeCrypto.decryptString(previousKek, blob);
        }
    }

    private void ensureKek() {
        if (kek == null) {
            throw new SecretUnavailableException(
                kekError == null ? "PRODUCTION_KEK 未配置" : kekError);
        }
    }
}
