package com.petsitter.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * local profile 的簽名上傳網址（SD-FULFILL §8.2）：模擬 GCS V4 簽名 PUT，網址內帶到期時間與 HMAC（綁定物件鍵、
 * Content-Type、大小、到期）。金鑰取 {@code app.storage.local.hmac-key}（只放 application-local.yml）；未設定時每次啟動隨機產生。
 */
@Component
@Profile("local")
public class LocalSignedUploads {

    public static final String PATH_PREFIX = "/local-storage/";

    private final byte[] key;

    public LocalSignedUploads(@Value("${app.storage.local.hmac-key:}") String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String sign(String objectKey, String contentType, long exactBytes, Instant expiresAt) {
        long exp = expiresAt.getEpochSecond();
        return PATH_PREFIX + objectKey + "?expires=" + exp + "&ct=" + URLEncoder.encode(contentType, StandardCharsets.UTF_8)
                + "&len=" + exactBytes + "&sig=" + mac(objectKey, contentType, exactBytes, exp);
    }

    public boolean verify(String objectKey, String contentType, long exactBytes, long expiresEpochSecond, String sig, Instant now) {
        if (sig == null || now.getEpochSecond() >= expiresEpochSecond) {
            return false;
        }
        String expected = mac(objectKey, contentType, exactBytes, expiresEpochSecond);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII));
    }

    private String mac(String objectKey, String contentType, long exactBytes, long exp) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = m.doFinal((objectKey + "\n" + contentType + "\n" + exactBytes + "\n" + exp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
