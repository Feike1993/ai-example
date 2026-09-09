package com.feike.ai.production.secret.manager;

import com.feike.ai.production.secret.service.SecretUnavailableException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Arrays;

/**
 * AES-256-GCM 信封加解密。
 * <p>
 * 密文进 Postgres，KEK 只留在进程环境变量里。这保护的是库备份和 {@code SELECT *}，
 * 不保护已经拿到 {@code PRODUCTION_KEK} 的应用进程。KEK 与密文必须分开放，
 * 不能把主密钥写进 {@code prod_secret}。
 */
public final class EnvelopeCrypto {

    /** GCM nonce 长度（字节）。 */
    public static final int NONCE_LENGTH = 12;

    /** 认证标签位数。 */
    public static final int TAG_BITS = 128;

    /** KEK 必须是 32 字节（AES-256）。 */
    public static final int KEK_LENGTH = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private EnvelopeCrypto() {}

    /**
     * 解析 Base64 编码的 32 字节 KEK。
     *
     * @param base64 {@code PRODUCTION_KEK}
     * @return AES 密钥
     * @throws SecretUnavailableException 缺失或长度不对
     */
    public static SecretKey parseKek(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new SecretUnavailableException("未配置 PRODUCTION_KEK，工业级接口无法解密钥");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new SecretUnavailableException("PRODUCTION_KEK 不是合法 Base64");
        }
        if (raw.length != KEK_LENGTH) {
            throw new SecretUnavailableException(
                "PRODUCTION_KEK 解码后必须是 " + KEK_LENGTH + " 字节，当前 " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * 加密明文。
     *
     * @param kek       主密钥
     * @param kekId     密钥版本，写入行内便于日后轮换
     * @param plaintext 明文
     * @return 密文与 nonce
     */
    public static EncryptedBlob encrypt(SecretKey kek, String kekId, byte[] plaintext) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        return encrypt(kek, kekId, plaintext, nonce);
    }

    /**
     * 使用指定 nonce 加密。仅测试固定向量时传入；生产路径走随机 nonce。
     *
     * @param kek       主密钥
     * @param kekId     密钥版本
     * @param plaintext 明文
     * @param nonce     12 字节
     * @return 密文与 nonce
     */
    public static EncryptedBlob encrypt(SecretKey kek, String kekId, byte[] plaintext, byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_LENGTH) {
            throw new SecretUnavailableException("GCM nonce 必须是 " + NONCE_LENGTH + " 字节");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext == null ? new byte[0] : plaintext);
            return new EncryptedBlob(ciphertext, nonce.clone(), kekId == null || kekId.isBlank() ? "v1" : kekId);
        } catch (GeneralSecurityException ex) {
            throw new SecretUnavailableException("信封加密失败: " + ex.getMessage());
        }
    }

    /**
     * 加密 UTF-8 字符串。
     *
     * @param kek       主密钥
     * @param kekId     密钥版本
     * @param plaintext 明文
     * @return 密文与 nonce
     */
    public static EncryptedBlob encryptString(SecretKey kek, String kekId, String plaintext) {
        byte[] bytes = plaintext == null ? new byte[0] : plaintext.getBytes(StandardCharsets.UTF_8);
        return encrypt(kek, kekId, bytes);
    }

    /**
     * 解密。
     *
     * @param kek  主密钥
     * @param blob 密文行
     * @return 明文
     * @throws SecretUnavailableException 认证失败（KEK 不对或数据被改）
     */
    public static byte[] decrypt(SecretKey kek, EncryptedBlob blob) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, blob.nonce()));
            return cipher.doFinal(blob.ciphertext());
        } catch (AEADBadTagException ex) {
            throw new SecretUnavailableException("密钥解密失败：KEK 不匹配或密文已损坏");
        } catch (GeneralSecurityException ex) {
            throw new SecretUnavailableException("信封解密失败: " + ex.getMessage());
        }
    }

    /**
     * 解密为 UTF-8 字符串。
     *
     * @param kek  主密钥
     * @param blob 密文行
     * @return 明文
     */
    public static String decryptString(SecretKey kek, EncryptedBlob blob) {
        byte[] plain = decrypt(kek, blob);
        try {
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * 生成 32 字节随机密钥并编码为 Base64，供首次签发 JWT HMAC 使用。
     *
     * @return Base64 文本
     */
    public static String randomKeyBase64() {
        byte[] raw = new byte[KEK_LENGTH];
        RANDOM.nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /**
     * 一行密文。
     *
     * @param ciphertext AES-GCM 输出（含 tag）
     * @param nonce      12 字节
     * @param kekId      密钥版本
     */
    public record EncryptedBlob(byte[] ciphertext, byte[] nonce, String kekId) {}
}
