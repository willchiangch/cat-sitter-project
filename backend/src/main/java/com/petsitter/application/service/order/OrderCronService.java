package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.application.service.fulfill.CronScope;
import com.petsitter.application.service.fulfill.VisitQueryService;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.OrderRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 排程協調者 C1–C10（SD-ORDER §8.1）：本身不開交易；唯讀候選（LIMIT 200、以注入 Clock 的 now 比對）→ 逐筆呼叫
 * {@link OrderCronItemProcessor}（每筆獨立交易）→ 計 processed / skipped / failed；時間預算 50 秒。
 * {@code orderId} 有值時只處理該測試帳號訂單（否則 404）；自動觸發時候選排除測試帳號訂單。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCronService {

    public record CronResult(String job, int processed, int skipped, int failed, OffsetDateTime startedAt,
                             OffsetDateTime finishedAt) {
    }

    private static final Pattern PROOF_KEY = Pattern.compile("^payment-proofs/[^/]+/[^/]+/[^/]+/([0-9a-fA-F-]{36})\\.[a-z]+$");
    static final int CLEANUP_BATCH = 5000;

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final PaymentSubmissionRepository submissionRepository;
    private final VisitQueryService visitQueryService;
    private final OrderCronItemProcessor processor;
    private final IdempotencyService idempotencyService;
    private final MediaStorageService mediaStorageService;
    private final JdbcTemplate jdbcTemplate;
    private final OrderSettings settings;
    private final OrderClock clock;

    /** {@code orderId} 有值時必須是測試帳號訂單（owner 或 sitter 為 @e2e-journey.test），否則 404。 */
    public CronScope scope(UUID orderId) {
        if (orderId != null && (!orderRepository.existsById(orderId) || !orderRepository.isTestAccountOrder(orderId))) {
            throw OrderException.notFound();
        }
        return new CronScope(orderId);
    }

    public CronResult reviewExpiry(UUID orderId) {
        CronScope s = scope(orderId);
        return run("orders/review-expiry", () -> orderRepository.findReviewExpiryCandidates(clock.now(), s.orderId(), batch()),
                processor::reviewExpiry);
    }

    public CronResult paymentReminder(UUID orderId) {
        CronScope s = scope(orderId);
        return run("payments/reminder", () -> paymentRepository.findReminderCandidates(clock.now(), s.orderId(), batch()),
                processor::paymentReminder);
    }

    public CronResult initialPaymentExpiry(UUID orderId) {
        CronScope s = scope(orderId);
        return run("payments/initial-expiry", () -> paymentRepository.findDueCandidates("INITIAL", clock.now(), s.orderId(), batch()),
                processor::initialPaymentExpiry);
    }

    public CronResult paymentEscalation(UUID orderId) {
        CronScope s = scope(orderId);
        return run("payments/escalation", () -> paymentRepository.findEscalationCandidates(clock.now(), s.orderId(), batch()),
                processor::paymentEscalation);
    }

    public CronResult modificationExpiry(UUID orderId) {
        CronScope s = scope(orderId);
        return run("modifications/expiry", () -> modificationRequestRepository.findExpiryCandidates(clock.now(), s.orderId(), batch()),
                processor::modificationExpiry);
    }

    public CronResult supplementExpiry(UUID orderId) {
        CronScope s = scope(orderId);
        return run("modifications/supplement-expiry", () -> paymentRepository.findDueCandidates("SUPPLEMENT", clock.now(), s.orderId(), batch()),
                processor::supplementExpiry);
    }

    public CronResult zombieVisits(UUID orderId) {
        CronScope s = scope(orderId);
        return run("visits/zombie", () -> {
            LocalDate maxDate = clock.now().minus(settings.visitZombie()).atZoneSameInstant(OrderClock.TAIPEI).toLocalDate();
            return visitQueryService.findOrderIdsWithZombieVisits(maxDate,
                    EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS, OrderStatus.DISPUTED), batch(), s);
        }, processor::zombieVisits);
    }

    public CronResult autoComplete(UUID orderId) {
        CronScope s = scope(orderId);
        return run("orders/auto-complete", () -> orderRepository.findAutoCompleteCandidates(
                clock.now().minus(settings.autoComplete()), s.orderId(), batch()), processor::autoComplete);
    }

    /** C9：分批刪除過期 idempotency_records 與舊表 idempotency_keys（created_at < now − 24h），每批一個短交易。 */
    public CronResult idempotencyCleanup() {
        OffsetDateTime started = clock.now();
        long deadline = System.nanoTime() + settings.getCronTimeBudget().toNanos();
        int processed = 0;
        int failed = 0;
        try {
            int n;
            do {
                n = idempotencyService.purgeExpiredBatch(started, CLEANUP_BATCH);
                processed += n;
            } while (n > 0 && System.nanoTime() < deadline);
            do {
                n = jdbcTemplate.update("""
                        DELETE FROM idempotency_keys WHERE ctid IN (
                          SELECT ctid FROM idempotency_keys WHERE created_at < ? LIMIT ?)
                        """, started.minusHours(24), CLEANUP_BATCH);
                processed += n;
            } while (n > 0 && System.nanoTime() < deadline);
        } catch (RuntimeException e) {
            log.error("idempotency cleanup failed", e);
            failed++;
        }
        return new CronResult("idempotency/cleanup", processed, 0, failed, started, clock.now());
    }

    /**
     * C10：掃 {@code payment-proofs/{d}/}（d = 今天往前 1–7 天，UTC），建立 > 24h、未被 submission 引用、對應認領紀錄非
     * PROCESSING 的物件 → 刪除；刪除失敗記 log 下次再試。不開 DB 寫入交易。
     */
    public CronResult paymentProofOrphans() {
        OffsetDateTime started = clock.now();
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        LocalDate today = started.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        for (int i = 1; i <= 7; i++) {
            String prefix = "payment-proofs/" + today.minusDays(i) + "/";
            List<MediaStorageService.StoredObject> objects;
            try {
                objects = mediaStorageService.listObjects(prefix);
            } catch (RuntimeException e) {
                log.error("list {} failed", prefix, e);
                failed++;
                continue;
            }
            Set<String> referenced = new LinkedHashSet<>();
            List<String> keys = objects.stream().map(MediaStorageService.StoredObject::key).toList();
            if (!keys.isEmpty()) {
                submissionRepository.findByProofObjectKeyIn(keys).forEach(s -> referenced.add(s.getProofObjectKey()));
            }
            for (MediaStorageService.StoredObject obj : objects) {
                if (obj.createdAt() == null || Duration.between(obj.createdAt(), started).toHours() < 24
                        || referenced.contains(obj.key()) || processing(obj.key())) {
                    skipped++;
                    continue;
                }
                try {
                    mediaStorageService.deleteObject(obj.key());
                    processed++;
                } catch (RuntimeException e) {
                    log.warn("orphan proof delete failed, retry next run: {}", obj.key());
                    failed++;
                }
            }
        }
        return new CronResult("storage/payment-proof-orphans", processed, skipped, failed, started, clock.now());
    }

    private boolean processing(String key) {
        Matcher m = PROOF_KEY.matcher(key);
        return m.matches() && idempotencyService.isProcessing(UUID.fromString(m.group(1)));
    }

    private int batch() {
        return settings.getCronBatchSize();
    }

    private CronResult run(String job, Supplier<List<UUID>> candidates, Function<UUID, Boolean> item) {
        OffsetDateTime started = clock.now();
        long deadline = System.nanoTime() + settings.getCronTimeBudget().toNanos();
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID orderId : new LinkedHashSet<>(candidates.get())) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                if (Boolean.TRUE.equals(item.apply(orderId))) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                log.error("cron {} failed for order {}", job, orderId, e);
                failed++;
            }
        }
        return new CronResult(job, processed, skipped, failed, started, clock.now());
    }
}
