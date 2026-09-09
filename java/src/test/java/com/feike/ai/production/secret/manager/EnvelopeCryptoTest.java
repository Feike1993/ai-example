package com.feike.ai.production.secret.manager;

import com.feike.ai.production.secret.service.SecretUnavailableException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("EnvelopeCrypto")
class EnvelopeCryptoTest {

    @Test
    void roundTripShouldRestorePlaintext() {
        SecretKey kek = EnvelopeCrypto.parseKek(EnvelopeCrypto.randomKeyBase64());
        EnvelopeCrypto.EncryptedBlob blob = EnvelopeCrypto.encryptString(kek, "v1", "sk-secret");
        assertEquals("sk-secret", EnvelopeCrypto.decryptString(kek, blob));
    }

    @Test
    void wrongKekShouldFailClosed() {
        SecretKey kek = EnvelopeCrypto.parseKek(EnvelopeCrypto.randomKeyBase64());
        SecretKey other = EnvelopeCrypto.parseKek(EnvelopeCrypto.randomKeyBase64());
        EnvelopeCrypto.EncryptedBlob blob = EnvelopeCrypto.encrypt(kek, "v1", "hello".getBytes(StandardCharsets.UTF_8));
        assertThrows(SecretUnavailableException.class, () -> EnvelopeCrypto.decrypt(other, blob));
    }

    @Test
    void blankKekShouldBeUnavailable() {
        assertThrows(SecretUnavailableException.class, () -> EnvelopeCrypto.parseKek(""));
    }

    @Test
    void encryptShouldNotEchoPlaintextInCiphertext() {
        SecretKey kek = EnvelopeCrypto.parseKek(EnvelopeCrypto.randomKeyBase64());
        byte[] plain = "super-secret-api-key".getBytes(StandardCharsets.UTF_8);
        EnvelopeCrypto.EncryptedBlob blob = EnvelopeCrypto.encrypt(kek, "v1", plain);
        assertArrayEquals(plain, EnvelopeCrypto.decrypt(kek, blob));
        String cipher = new String(blob.ciphertext(), StandardCharsets.ISO_8859_1);
        org.junit.jupiter.api.Assertions.assertFalse(cipher.contains("super-secret"));
    }

    @Test
    void sameKekAndNonceShouldProduceDeterministicCiphertext() {
        byte[] nonce = new byte[EnvelopeCrypto.NONCE_LENGTH];
        byte[] kekRaw = new byte[EnvelopeCrypto.KEK_LENGTH];
        java.util.Arrays.fill(kekRaw, (byte) 7);
        SecretKey kek = new javax.crypto.spec.SecretKeySpec(kekRaw, "AES");
        EnvelopeCrypto.EncryptedBlob first = EnvelopeCrypto.encrypt(kek, "v1", "hello".getBytes(StandardCharsets.UTF_8), nonce);
        EnvelopeCrypto.EncryptedBlob second = EnvelopeCrypto.encrypt(kek, "v1", "hello".getBytes(StandardCharsets.UTF_8), nonce);
        assertArrayEquals(first.ciphertext(), second.ciphertext());
        assertEquals("hello", EnvelopeCrypto.decryptString(kek, first));
    }
}
