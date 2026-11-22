package com.petsitter.application.service.order;

import com.petsitter.application.port.PayoutAccountQuery.PayoutAccountSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 收款帳號快照加解密（SD-ORDER §2.3.2、§11.4「收款帳號快照儲存」）：四欄 JSON 整體以 AES-256-GCM 加密成單一欄，
 * 帶 {@code payee_account_key_version}。金鑰 {@code app.crypto.key} 不得有預設值（CQ-72）。明文不落 log。
 * <p>
 * SD-ORDER §8.2（NFR-003 U-8、CQ-48）：AAD = UTF-8 字串 {@code payments:{paymentId}:payee_account_snapshot}，加解密都帶；
 * 密文被搬到別列（AAD 不符）或竄改任一位元組 → 解密失敗 → 500 {@code INTERNAL_ERROR}。不提供「無 AAD 也接受」的相容分支。
 */
@Component
public class PayoutSnapshotCipher {

    public static final short KEY_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final JsonMapper jsonMapper;
    private final SecureRandom random = new SecureRandom();

    public PayoutSnapshotCipher(@Value("${app.crypto.key}") String base64Key, JsonMapper jsonMapper) {
        byte[] bytes = Base64.getDecoder().decode(base64Key);
        if (bytes.length != 32) {
            throw new IllegalStateException("app.crypto.key must be a base64 encoded 256-bit key");
        }
        this.key = new SecretKeySpec(bytes, "AES");
        this.jsonMapper = jsonMapper;
    }

    static byte[] aad(UUID paymentId) {
        return ("payments:" + paymentId + ":payee_account_snapshot").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code paymentId} 由 Service 在加密前產生，INSERT 時寫入同一值。 */
    public String encrypt(PayoutAccountSnapshot snapshot, UUID paymentId) {
        java.util.Objects.requireNonNull(paymentId, "paymentId");
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("bankCode", snapshot.bankCode());
            m.put("branch", snapshot.branch());
            m.put("accountNumber", snapshot.accountNumber());
            m.put("accountName", snapshot.accountName());
            byte[] plain = jsonMapper.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(paymentId));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("payout snapshot encryption failed");
        }
    }

    /** 解密失敗 → 500（不回傳部分欄位，SD-ORDER §8.2）。 */
    public PayoutAccountSnapshot decrypt(String ciphertext, short keyVersion, UUID paymentId) {
        if (keyVersion != KEY_VERSION) {
            throw new IllegalStateException("unknown payout snapshot key version");
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            cipher.updateAAD(aad(paymentId));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            JsonNode n = jsonMapper.readTree(new String(plain, StandardCharsets.UTF_8));
            return new PayoutAccountSnapshot(text(n, "bankCode"), text(n, "branch"), text(n, "accountNumber"),
                    text(n, "accountName"));
        } catch (Exception e) {
            throw new IllegalStateException("payout snapshot decryption failed");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
