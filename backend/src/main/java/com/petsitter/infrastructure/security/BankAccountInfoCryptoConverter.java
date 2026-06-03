package com.petsitter.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.domain.model.BankAccountInfo;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Converter
@Component
public class BankAccountInfoCryptoConverter implements AttributeConverter<BankAccountInfo, String> {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final String secretKeyString;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BankAccountInfoCryptoConverter(@Value("${app.crypto.key}") String secretKeyString) {
        this.secretKeyString = secretKeyString;
    }

    @Override
    public String convertToDatabaseColumn(BankAccountInfo attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(secretKeyString);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

            String json = objectMapper.writeValueAsString(attribute);
            byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));

            byte[] encryptedWithIv = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, encryptedWithIv, 0, iv.length);
            System.arraycopy(encrypted, 0, encryptedWithIv, iv.length, encrypted.length);

            String ciphertext = Base64.getEncoder().encodeToString(encryptedWithIv);
            return objectMapper.writeValueAsString(Map.of("ciphertext", ciphertext));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt bank info", e);
        }
    }

    @Override
    public BankAccountInfo convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(dbData, Map.class);
            String ciphertext = (String) map.get("ciphertext");
            if (ciphertext == null) {
                return null;
            }

            byte[] keyBytes = Base64.getDecoder().decode(secretKeyString);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
            byte[] encryptedWithIv = Base64.getDecoder().decode(ciphertext);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(encryptedWithIv, 0, iv, 0, iv.length);
            byte[] encrypted = new byte[encryptedWithIv.length - iv.length];
            System.arraycopy(encryptedWithIv, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return objectMapper.readValue(new String(decrypted, StandardCharsets.UTF_8), BankAccountInfo.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt bank info", e);
        }
    }
}
