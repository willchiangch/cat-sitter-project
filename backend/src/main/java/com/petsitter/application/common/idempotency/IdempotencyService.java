package com.petsitter.application.common.idempotency;

import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import com.petsitter.application.exception.IdempotencyException;
import com.petsitter.application.exception.RequestValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * 冪等三段式（SD-GLOBAL-SPEC §2.4、SD-ORDER §3.6.2）。任何模組可依賴。
 * <ul>
 *   <li>{@link #claim}：傳播 {@code NEVER}（呼叫端不得已在交易中），自行開短交易 A</li>
 *   <li>{@link #complete}：傳播 {@code MANDATORY}，在呼叫端的業務交易 B 內；不要求持有任何業務鎖，只比對 {@code claim_token}</li>
 *   <li>{@link #fail}：傳播 {@code NEVER}，自行開短交易 C；通常由 {@link IdempotencyInterceptor#afterCompletion} 自動呼叫</li>
 * </ul>
 * 每個請求同一時間只佔 1 條連線（不用 {@code REQUIRES_NEW}）。
 */
@Slf4j
@Service
public class IdempotencyService {

    /** 處理中鎖定期間（baseline §3.6 CQ-51）。 */
    public static final Duration PROCESSING_LOCK = Duration.ofSeconds(30);

    static final String STATE_ATTRIBUTE = IdempotencyService.class.getName() + ".state";

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate shortTx;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyService(IdempotencyRecordRepository repository,
                              PlatformTransactionManager transactionManager,
                              JsonMapper jsonMapper,
                              Clock clock,
                              @Value("${app.idempotency.ttl-hours:24}") long ttlHours) {
        this.repository = repository;
        this.shortTx = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.ttl = Duration.ofHours(ttlHours);
    }

    /**
     * 本請求的冪等身分。缺 {@code Idempotency-Key}、key 格式不符（1–128 個可見 ASCII）、端點未標 {@link Idempotent}
     * 或未登入時為 empty —— 模組在判斷順序第 6 步據此回 400 {@code VALIDATION_FAILED}（{@code field=Idempotency-Key}）。
     */
    public Optional<IdempotencyContext> currentContext() {
        return Optional.ofNullable(currentState()).map(s -> s.context);
    }

    /** {@link #currentContext()} 的便利版：缺 key → {@link RequestValidationException}（400，{@code field=Idempotency-Key}）。 */
    public IdempotencyContext requireContext() {
        return currentContext().orElseThrow(() -> new RequestValidationException(IdempotencyBodyCachingFilter.HEADER));
    }

    /**
     * 段 1 認領（判斷順序第 7 步，於 404 / 403 / 400 之後）。
     *
     * @throws IdempotencyException {@code REUSED}（同 key 不同 hash → 422）、{@code IN_PROGRESS}（處理中 → 409）
     */
    @Transactional(propagation = Propagation.NEVER)
    public IdempotencyClaim claim(IdempotencyContext ctx) {
        IdempotencyClaim claim = shortTx.execute(status -> doClaim(ctx));
        RequestState state = currentState();
        if (state != null && state.context != null && state.context.equals(ctx)) {
            state.claim = claim;
        }
        return claim;
    }

    private IdempotencyClaim doClaim(IdempotencyContext ctx) {
        OffsetDateTime now = now();
        OffsetDateTime lockedUntil = now.plus(PROCESSING_LOCK);
        UUID token = UUID.randomUUID();
        Optional<IdempotencyRecordRepository.Claimed> inserted = repository.insertOrReplaceExpired(
                UUID.randomUUID(), ctx, token, now, lockedUntil, now.plus(ttl));
        if (inserted.isPresent()) {
            return new IdempotencyClaim(inserted.get().id(), inserted.get().claimToken(), false, ctx);
        }
        IdempotencyRecordRepository.Row existing = repository.findByScope(ctx.userId(), ctx.scope(), ctx.key())
                .orElseThrow(() -> new IdempotencyException(IdempotencyException.Kind.IN_PROGRESS));
        if (!existing.requestHash().equals(ctx.requestHash())) {
            throw new IdempotencyException(IdempotencyException.Kind.REUSED);
        }
        if (IdempotencyRecordRepository.PROCESSING.equals(existing.status())
                && existing.lockedUntil() != null && !existing.lockedUntil().isAfter(now)) {
            UUID newToken = UUID.randomUUID();
            if (repository.takeOver(existing.id(), newToken, now, lockedUntil)) {
                return new IdempotencyClaim(existing.id(), newToken, true, ctx);
            }
        }
        // PROCESSING 且未過 locked_until、接手被別人搶先，或段 0 之後才剛 COMPLETED（同 hash）：
        // 一律 409，客戶端同 key 重試時由段 0 回放。
        throw new IdempotencyException(IdempotencyException.Kind.IN_PROGRESS);
    }

    /**
     * 段 3 完成：在呼叫端的業務交易內把紀錄改 {@code COMPLETED} 並存回應。
     * 影響 0 列（已被接手）→ 拋 {@link IdempotencyException}（{@code IN_PROGRESS}），業務交易隨之回滾。
     *
     * @param body 回應 body（以應用程式的 JsonMapper 序列化，與實際回應同為 non-null 輸出；不得含簽名網址等時效性資料）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(IdempotencyClaim claim, int responseStatus, Object body) {
        String json = body == null ? null : jsonMapper.writeValueAsString(body);
        if (!repository.markCompleted(claim.recordId(), claim.claimToken(), responseStatus, json)) {
            throw new IdempotencyException(IdempotencyException.Kind.IN_PROGRESS);
        }
        RequestState state = currentState();
        if (state != null && state.claim != null && state.claim.equals(claim)
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    state.completedCommitted = true;
                }
            });
        }
    }

    /**
     * 失敗段（交易 C）。所有寫入都帶 {@code id + status='PROCESSING' + claim_token} 條件。
     * <ul>
     *   <li>409 / 422 且碼不是 {@code ORDER_BUSY} / {@code IDEMPOTENCY_IN_PROGRESS} → 記為 COMPLETED（可回放）</li>
     *   <li>409 {@code IDEMPOTENCY_IN_PROGRESS}（被接手）→ 不記錄、不刪除</li>
     *   <li>其他（5xx、{@code ORDER_BUSY}、{@code STORAGE_UNAVAILABLE}、2xx 卻未完成）→ 刪除紀錄，允許同 key 重試</li>
     * </ul>
     *
     * @return {@link IdempotencyFailOutcome#DELETED} / {@code RECORDED} 表示本請求在此刻仍持有 token
     *         （E11 依此決定是否可刪 GCS 物件）
     */
    @Transactional(propagation = Propagation.NEVER)
    public IdempotencyFailOutcome fail(IdempotencyClaim claim, int responseStatus, ErrorResponse errorBody) {
        RequestState state = currentState();
        if (state != null && claim.equals(state.claim)) {
            state.failHandled = true;
        }
        String code = errorBody == null ? null : errorBody.code();
        if (CommonErrorCode.IDEMPOTENCY_IN_PROGRESS.name().equals(code)) {
            return IdempotencyFailOutcome.UNTOUCHED;
        }
        boolean recordable = (responseStatus == 409 || responseStatus == 422)
                && errorBody != null
                && !CommonErrorCode.ORDER_BUSY.name().equals(code);
        Boolean held = shortTx.execute(status -> recordable
                ? repository.markCompleted(claim.recordId(), claim.claimToken(), responseStatus,
                        jsonMapper.writeValueAsString(errorBody))
                : repository.deleteHeld(claim.recordId(), claim.claimToken()));
        if (!Boolean.TRUE.equals(held)) {
            return IdempotencyFailOutcome.NOT_HELD;
        }
        return recordable ? IdempotencyFailOutcome.RECORDED : IdempotencyFailOutcome.DELETED;
    }

    /** 段 0：已完成、未過期、同 hash → 第一次的結果（單一唯讀查詢，不開交易）。 */
    public Optional<IdempotencyStoredResponse> findReplay(IdempotencyContext ctx) {
        OffsetDateTime now = now();
        return repository.findByScope(ctx.userId(), ctx.scope(), ctx.key())
                .filter(r -> IdempotencyRecordRepository.COMPLETED.equals(r.status()))
                .filter(r -> r.expiresAt().isAfter(now))
                .filter(r -> r.requestHash().equals(ctx.requestHash()))
                .map(r -> new IdempotencyStoredResponse(r.responseStatus(), r.responseBody()));
    }

    /** 請求結束時由攔截器呼叫：claim 後沒有成功提交 complete 的請求走失敗段。 */
    public void onRequestCompleted(RequestState state, int responseStatus, ErrorResponse errorBody, boolean unhandledError) {
        if (state.claim == null || state.failHandled) {
            return;
        }
        boolean success = !unhandledError && responseStatus >= 200 && responseStatus < 300;
        if (success && state.completedCommitted) {
            return;
        }
        int status = unhandledError ? 500 : responseStatus;
        if (success) {
            log.warn("idempotent request returned {} without complete(); deleting claim {}", status,
                    state.claim.recordId());
        }
        try {
            fail(state.claim, status, success ? null : errorBody);
        } catch (RuntimeException e) {
            // 刪不掉時紀錄會在 locked_until 過後被接手；不影響已送出的回應
            log.error("idempotency fail-stage error for record {}", state.claim.recordId(), e);
        }
    }

    public RequestState currentState() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object state = attributes.getAttribute(STATE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return state instanceof RequestState s ? s : null;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** 每個請求一份的冪等狀態（request attribute）。 */
    static final class RequestState {
        final IdempotencyContext context;
        volatile IdempotencyClaim claim;
        volatile boolean completedCommitted;
        volatile boolean failHandled;

        RequestState(IdempotencyContext context) {
            this.context = context;
        }
    }
}
