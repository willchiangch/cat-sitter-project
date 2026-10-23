package com.petsitter.infrastructure.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SD-007 §4.7：付款憑證舊資料存公開網址、新資料存 Object Key，resolveObjectKey 要兩種都能正規化。
 * 不呼叫 @PostConstruct init()，不需要 GCP 憑證。
 */
@DisplayName("TS-007 B1: MediaStorageService.resolveObjectKey 相容舊公開網址")
class MediaStorageObjectKeyTest {

    @Test
    @DisplayName("GCS：公開網址剝前綴、Object Key 原樣回傳、null 回 null")
    void gcs_resolveObjectKey() {
        GcsMediaStorageServiceImpl gcs = new GcsMediaStorageServiceImpl();
        ReflectionTestUtils.setField(gcs, "bucketName", "cat-sitter-media-bucket");

        assertEquals("payment-proofs/2026-09-01/o_x.jpg",
                gcs.resolveObjectKey("https://storage.googleapis.com/cat-sitter-media-bucket/payment-proofs/2026-09-01/o_x.jpg"));
        assertEquals("payment-proofs/2026-10-24/o_y.jpg", gcs.resolveObjectKey("payment-proofs/2026-10-24/o_y.jpg"));
        // 別的 bucket 的網址不能被剝成看似合法的 key
        assertEquals("https://storage.googleapis.com/other-bucket/payment-proofs/a.jpg",
                gcs.resolveObjectKey("https://storage.googleapis.com/other-bucket/payment-proofs/a.jpg"));
        assertNull(gcs.resolveObjectKey(null));
    }

    @Test
    @DisplayName("Local：本機舊網址剝前綴、Object Key 原樣回傳")
    void local_resolveObjectKey() {
        LocalMediaStorageServiceImpl local = new LocalMediaStorageServiceImpl();
        ReflectionTestUtils.setField(local, "localBaseUrl", "http://localhost:8080/local-media");

        assertEquals("payment-proofs/2026-09-01/o_x.jpg",
                local.resolveObjectKey("http://localhost:8080/local-media/payment-proofs/2026-09-01/o_x.jpg"));
        assertEquals("payment-proofs/2026-10-24/o_y.jpg", local.resolveObjectKey("payment-proofs/2026-10-24/o_y.jpg"));
    }
}
