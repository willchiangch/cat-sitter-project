package com.petsitter.application.service.fulfill;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.application.service.order.OrderClock;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 日誌媒體簽名讀取網址（SD-FULFILL §8.2、§11 決策 13）：V4 簽名 GET（TTL 15 分鐘），同一實例以 objectKey 為鍵快取 5 分鐘，
 * 回傳簽名時的原始到期時間。授權由呼叫端每次檢查；不寫入 DB 與冪等 body。必須在交易外呼叫。
 */
@Component
public class MediaUrlSigner {

    public record SignedUrl(String url, OffsetDateTime expiresAt) {
    }

    private final MediaStorageService storage;
    private final FulfillSettings settings;
    private final OrderClock clock;
    private final Cache<String, SignedUrl> cache;

    public MediaUrlSigner(MediaStorageService storage, FulfillSettings settings, OrderClock clock) {
        this.storage = storage;
        this.settings = settings;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(settings.getMediaUrlCacheTtl()).maximumSize(10_000).build();
    }

    public SignedUrl sign(String objectKey) {
        SignedUrl cached = cache.getIfPresent(objectKey);
        OffsetDateTime now = clock.now();
        // 快取以實際時間過期；另以注入 Clock 確認交付時剩餘 ≥ TTL − 快取期限（測試可推進時鐘）
        if (cached != null && !now.isAfter(cached.expiresAt().minus(settings.getMediaUrlTtl()).plus(settings.getMediaUrlCacheTtl()))) {
            return cached;
        }
        SignedUrl fresh = new SignedUrl(storage.generateSignedUrl(objectKey, settings.getMediaUrlTtl()), now.plus(settings.getMediaUrlTtl()));
        cache.put(objectKey, fresh);
        return fresh;
    }

    public void invalidate(String objectKey) {
        cache.invalidate(objectKey);
    }
}
