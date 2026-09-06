package com.research.assistant.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptorTest {

    @Test
    void roundTripWithMasterKey() {
        Encryptor encryptor = new Encryptor("my-secret-master-key");
        String plain = "sk-xxxxxxxxxxxxxxxx";

        String encrypted = encryptor.encryptIfNeeded("api_key", plain);
        assertThat(encrypted).startsWith("enc:").isNotEqualTo(plain);

        String decrypted = encryptor.decryptIfNeeded(encrypted);
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void nonSensitiveKeyIsNotEncrypted() {
        Encryptor encryptor = new Encryptor("my-secret-master-key");
        String value = "https://api.example.com";

        String result = encryptor.encryptIfNeeded("base_url", value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    void noMasterKeyReturnsPlaintext() {
        Encryptor encryptor = new Encryptor("");
        String value = "sk-xxxxxxxxxxxxxxxx";

        String result = encryptor.encryptIfNeeded("api_key", value);
        assertThat(result).isEqualTo(value);
    }

    @Test
    void shouldEncryptApiKeyVariants() {
        Encryptor encryptor = new Encryptor("key");
        assertThat(encryptor.shouldEncrypt("api_key")).isTrue();
        assertThat(encryptor.shouldEncrypt("apiKey")).isTrue();
        assertThat(encryptor.shouldEncrypt("embedding_api_key")).isTrue();
        assertThat(encryptor.shouldEncrypt("provider_api_key")).isTrue();
        assertThat(encryptor.shouldEncrypt("model")).isFalse();
        assertThat(encryptor.shouldEncrypt("provider_token")).isTrue();
        assertThat(encryptor.shouldEncrypt("provider_secret")).isTrue();
    }

    @Test
    void alreadyEncryptedValueIsNotDoubleEncrypted() {
        Encryptor encryptor = new Encryptor("my-secret-master-key");
        String plain = "sk-xxxxxxxxxxxxxxxx";
        String encrypted = encryptor.encryptIfNeeded("api_key", plain);

        String again = encryptor.encryptIfNeeded("api_key", encrypted);
        assertThat(again).isEqualTo(encrypted);
    }

    @Test
    void failClosedRejectsMissingMasterKey() {
        assertThatThrownBy(() -> new Encryptor("", true))
                .isInstanceOf(IllegalStateException.class);
    }
}
