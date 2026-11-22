package testsupport.fulfill;

import com.petsitter.application.exception.MediaInspectionException;
import com.petsitter.application.exception.StorageUnavailableException;
import com.petsitter.application.service.MediaStorageService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可程式化的 GCS fake（SD-FULFILL §8.2 / §10.1）：記憶體物件（含 generation）、可注入簽名 / 讀取 / 刪除失敗、可預置物件、
 * 記錄每次 I/O 是否發生在交易內（驗證「外部 I/O 不在交易內」，§2.5 第 4 點）。
 */
public class FakeMediaStorageService implements MediaStorageService {

    public record Obj(byte[] bytes, long generation, String contentType) {
    }

    private final Map<String, Obj> objects = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong(1000);
    private final List<String> ioInsideTransaction = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> publicObjects = ConcurrentHashMap.newKeySet();
    public final AtomicInteger signReadCalls = new AtomicInteger();
    public volatile boolean failSign;
    public volatile boolean failRead;
    public volatile boolean failDelete;
    public volatile Duration lastUploadTtl;
    /** F18 讀取物件時（交易外）呼叫，供併發測試在檢驗與交易 B 之間插入動作。 */
    public volatile Runnable onRead;

    public void reset() {
        objects.clear();
        ioInsideTransaction.clear();
        publicObjects.clear();
        signReadCalls.set(0);
        failSign = false;
        failRead = false;
        failDelete = false;
        lastUploadTtl = null;
        onRead = null;
    }

    /** 模擬瀏覽器 PUT（if-generation-match=0：已存在則失敗）。回傳 generation。 */
    public long put(String key, byte[] bytes, String contentType) {
        Obj obj = new Obj(bytes, generations.incrementAndGet(), contentType);
        Obj prev = objects.putIfAbsent(key, obj);
        if (prev != null) {
            throw new IllegalStateException("412 object exists: " + key);
        }
        return obj.generation();
    }

    /** 直接覆寫（模擬遲到 PUT 重寫）。 */
    public long overwrite(String key, byte[] bytes) {
        Obj obj = new Obj(bytes, generations.incrementAndGet(), null);
        objects.put(key, obj);
        return obj.generation();
    }

    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    public int objectCount() {
        return objects.size();
    }

    public void markPublic(String key) {
        publicObjects.add(key);
    }

    public List<String> ioInsideTransaction() {
        return List.copyOf(ioInsideTransaction);
    }

    private void io(String op) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            ioInsideTransaction.add(op);
        }
    }

    @Override
    public String createSignedUploadUrl(String objectKey, String contentType, long exactBytes, Duration ttl) {
        io("sign-upload " + objectKey);
        if (failSign) {
            throw new StorageUnavailableException("fake sign failure", null);
        }
        lastUploadTtl = ttl;
        return "https://storage.googleapis.com/test-bucket/" + objectKey + "?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Expires="
                + ttl.toSeconds() + "&X-Goog-Signature=fake";
    }

    @Override
    public Optional<PrivateObject> openPrivateObject(String objectKey) {
        io("read " + objectKey);
        if (failRead) {
            throw new MediaInspectionException("fake read failure", null);
        }
        Runnable hook = onRead;
        if (hook != null) {
            hook.run();
        }
        Obj obj = objects.get(objectKey);
        if (obj == null) {
            return Optional.empty();
        }
        InputStream in = new ByteArrayInputStream(obj.bytes());
        return Optional.of(new PrivateObject() {
            @Override
            public long generation() {
                return obj.generation();
            }

            @Override
            public InputStream stream() {
                return in;
            }

            @Override
            public void close() {
            }
        });
    }

    @Override
    public boolean deletePrivateObject(String objectKey, Long generation) {
        io("delete " + objectKey);
        if (failDelete) {
            throw new IllegalStateException("fake delete failure");
        }
        Obj obj = objects.get(objectKey);
        if (obj == null) {
            return true;
        }
        if (generation != null && obj.generation() != generation) {
            return false;
        }
        objects.remove(objectKey);
        return true;
    }

    @Override
    public String generateSignedUrl(String objectKey, Duration ttl) {
        io("sign-read " + objectKey);
        if (failSign) {
            throw new StorageUnavailableException("fake sign failure", null);
        }
        signReadCalls.incrementAndGet();
        return "https://storage.googleapis.com/test-bucket/" + objectKey + "?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Expires="
                + ttl.toSeconds() + "&X-Goog-Signature=" + UUID.randomUUID();
    }

    @Override
    public String generateSignedUrl(String objectKey, Duration ttl, String contentType) {
        return generateSignedUrl(objectKey, ttl) + "&response-content-type="
                + java.net.URLEncoder.encode(contentType, java.nio.charset.StandardCharsets.UTF_8)
                + "&response-content-disposition=inline";
    }

    @Override
    public boolean isPubliclyReadable(String objectKey) {
        return publicObjects.contains(objectKey);
    }

    @Override
    public boolean revokePublicRead(String objectKey) {
        return publicObjects.remove(objectKey);
    }

    // ------------------------------------------------------------------ 其他模組（記憶體實作）

    @Override
    public String uploadMedia(UUID sitterId, UUID ownerId, UUID mediaId, MultipartFile file) {
        return "care_media/" + mediaId;
    }

    @Override
    public String uploadPetAvatar(UUID ownerId, UUID petId, MultipartFile file) {
        return "pet-avatars/" + petId;
    }

    @Override
    public String uploadPaymentProof(UUID ownerId, UUID orderId, MultipartFile file) {
        return "payment-proofs/" + orderId + "_" + UUID.randomUUID();
    }

    @Override
    public String uploadRefundProof(UUID sitterId, UUID orderId, MultipartFile file) {
        return "refund-proofs/" + orderId + "_" + UUID.randomUUID();
    }

    @Override
    public String uploadKycFile(UUID sitterId, String type, MultipartFile file) {
        return "kyc/" + sitterId + "/" + type;
    }

    @Override
    public String uploadAvatar(UUID sitterId, MultipartFile file) {
        return "avatars/" + sitterId;
    }

    @Override
    public void deleteMedia(String mediaUrl) {
        objects.remove(mediaUrl);
    }

    @Override
    public String resolveObjectKey(String storedRef) {
        return storedRef;
    }

    @Override
    public List<String> listObjectKeys(String prefix) {
        return objects.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
    }

    @Override
    public void uploadPrivateIfAbsent(String objectKey, byte[] content, String contentType) {
        objects.putIfAbsent(objectKey, new Obj(content, generations.incrementAndGet(), contentType));
    }

    @Override
    public boolean deleteObject(String objectKey) {
        return objects.remove(objectKey) != null;
    }

    @Override
    public List<StoredObject> listObjects(String prefix) {
        return objects.keySet().stream().filter(k -> k.startsWith(prefix))
                .map(k -> new StoredObject(k, OffsetDateTime.now(ZoneOffset.UTC))).toList();
    }
}
