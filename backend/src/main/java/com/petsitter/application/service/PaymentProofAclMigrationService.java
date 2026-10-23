package com.petsitter.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性維運：把 2026-10 之前以 allUsers READER 上傳的付款憑證改成私有物件 (SD-007 §5.1.1)。
 * 掃的是 GCS 上 payment-proofs/ 前綴的所有物件，而不是 DB 的 payment_proof_url：
 * 被保母駁回的憑證 DB 參照會被清空，但 GCS 物件不刪（財務舉證永久保存），一樣是公開的。
 * 預設 dry-run 只回報；可重複執行，已私有的物件不會再動。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProofAclMigrationService {

    static final int MAX_LISTED_KEYS = 200;

    private final MediaStorageService mediaStorageService;

    public Map<String, Object> revokePublicAcl(boolean dryRun) {
        List<String> keys = mediaStorageService.listObjectKeys(PaymentProofAccessService.PAYMENT_PROOF_PREFIX);
        List<String> publicKeys = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int revoked = 0;

        for (String key : keys) {
            try {
                if (!mediaStorageService.isPubliclyReadable(key)) {
                    continue;
                }
                publicKeys.add(key);
                if (!dryRun && mediaStorageService.revokePublicRead(key)) {
                    revoked++;
                }
            } catch (RuntimeException e) {
                log.error("[PaymentProofAclMigration] failed on object={}", key, e);
                failures.add(key + ": " + e.getMessage());
            }
        }

        log.info("[PaymentProofAclMigration] dryRun={}, scanned={}, public={}, revoked={}, failed={}",
                dryRun, keys.size(), publicKeys.size(), revoked, failures.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("scanned", keys.size());
        result.put("publicFound", publicKeys.size());
        result.put("revoked", revoked);
        result.put("failed", failures.size());
        result.put("publicKeys", publicKeys.subList(0, Math.min(publicKeys.size(), MAX_LISTED_KEYS)));
        result.put("failures", failures);
        return result;
    }
}
