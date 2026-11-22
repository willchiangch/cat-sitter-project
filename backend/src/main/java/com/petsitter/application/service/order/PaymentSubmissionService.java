package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyFailOutcome;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.dto.order.OrderCommandResponses;
import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.ErrorResponse;
import com.petsitter.application.exception.IdempotencyException;
import com.petsitter.application.exception.OrderErrorCode;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * E11 P1 上傳付款 / 退款憑證（SD-ORDER §3.6.2 三段式、§3.6.6、§6.3 E11）：
 * 鎖前檢查 → 認領（交易 A）→ 預檢（不取鎖）→ GCS 上傳（交易外，物件鍵由認領紀錄 id 決定）→ 交易 B（鎖後重驗）。
 * 交易 B 失敗：本服務先走失敗段，確認仍持有 claim_token 且無 submission 引用物件鍵才刪物件，否則交給 C10。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSubmissionService {

    private static final Pattern LAST_FIVE = Pattern.compile("^[0-9]{5}$");

    private final OrderCommandSupport support;
    private final OrderRequestValidator validator;
    private final IdempotencyService idempotencyService;
    private final PaymentSubmissionRepository submissionRepository;
    private final MediaStorageService mediaStorageService;
    private final OrderLogWriter orderLogWriter;
    private final OrderNotifier notifier;
    private final OrderSettings settings;
    private final OrderClock clock;

    record ProofFile(byte[] bytes, String contentType, String ext, String sha256) {
    }

    /**
     * @param receivedAt 伺服器收到請求的時間（Controller 進入時以注入 Clock 取得；期限一律以它判斷，PRD-007 BR-12）
     */
    public OrderCommandResponses.Submitted submit(UUID orderId, UUID paymentId, String disclaimerAgreed, String lastFive,
                                                  List<MultipartFile> files, OffsetDateTime receivedAt) {
        CurrentActor actor = CurrentActor.fromToken();
        Order order = support.loadOrder(orderId);
        Payment payment = support.loadPayment(order, paymentId);
        ActorRole role = OrderAccess.partyRole(order, actor);
        if (!OrderCommandSupport.isAdminAdjustment(payment) && role != OrderCommandSupport.payer(payment)) {
            throw OrderException.forbidden();
        }
        // G6
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        if (!"true".equals(disclaimerAgreed)) {
            throw OrderException.validation("disclaimerAgreed");
        }
        if (lastFive == null || !LAST_FIVE.matcher(lastFive).matches()) {
            throw OrderException.validation("lastFive");
        }
        if (files == null || files.size() != 1 || files.get(0) == null || files.get(0).isEmpty()) {
            throw OrderException.validation("file");
        }
        IdempotencyClaim claim = support.claim(ctx);
        // 預檢（不取鎖）
        precheck(order, payment, receivedAt);
        ProofFile proof = inspect(files.get(0));
        String objectKey = "payment-proofs/" + receivedAt.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "/" + orderId + "/" + paymentId + "/" + claim.recordId() + "." + proof.ext();
        // 段 2：交易外上傳；失敗 → 503（攔截器刪冪等紀錄，允許同 key 重試）
        mediaStorageService.uploadPrivateIfAbsent(objectKey, proof.bytes(), proof.contentType());
        try {
            return support.inLockedTx(orderId, locked -> applyInTx(locked, actor, role, paymentId, lastFive, proof,
                    objectKey, receivedAt, claim));
        } catch (RuntimeException e) {
            cleanupAfterFailure(claim, objectKey, e);
            throw e;
        }
    }

    private void precheck(Order order, Payment payment, OffsetDateTime receivedAt) {
        if (order.getStatus() == OrderStatus.DISPUTED) {
            throw OrderException.stateConflict(order.getStatus());
        }
        if (payment.getStatus() != PaymentStatus.UNPAID || OrderCommandSupport.isAdminAdjustment(payment)) {
            throw OrderException.paymentConflict(payment.getStatus());
        }
        if (payment.getDueAt() != null && !receivedAt.isBefore(payment.getDueAt())) {
            throw OrderException.deadline("PAYMENT_DUE_AT", payment.getDueAt());
        }
    }

    ProofFile inspect(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw OrderException.validation("file");
        }
        String type;
        String ext;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            type = "image/jpeg";
            ext = "jpg";
        } else if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            type = "image/png";
            ext = "png";
        } else if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            type = "image/webp";
            ext = "webp";
        } else {
            throw new OrderException(OrderErrorCode.PROOF_FILE_TYPE_UNSUPPORTED);
        }
        if (bytes.length > settings.getProofMaxBytes()) {
            throw new OrderException(OrderErrorCode.PROOF_FILE_TOO_LARGE, Map.of("maxBytes", settings.getProofMaxBytes()));
        }
        try {
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ProofFile(bytes, type, ext, sha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderCommandResponses.Submitted applyInTx(LockedOrder locked, CurrentActor actor, ActorRole role, UUID paymentId,
                                                      String lastFive, ProofFile proof, String objectKey,
                                                      OffsetDateTime receivedAt, IdempotencyClaim claim) {
        Order order = locked.entity();
        Payment payment = support.loadPayment(order, paymentId);
        precheck(order, payment, receivedAt);
        PaymentSubmission submission = submissionRepository.save(PaymentSubmission.builder()
                .paymentId(payment.getId())
                .submittedBy(actor.userId())
                .lastFive(lastFive)
                .disclaimerAgreedAt(receivedAt)
                .proofObjectKey(objectKey)
                .proofSha256(proof.sha256())
                .proofContentType(proof.contentType())
                .proofSizeBytes(proof.bytes().length)
                .status(SubmissionStatus.SUBMITTED)
                .submittedAt(receivedAt)
                .build());
        payment.setStatus(PaymentStatus.SUBMITTED);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.getId().toString());
        payload.put("purpose", payment.getPurpose().name());
        payload.put("submissionId", submission.getId().toString());
        payload.put("lastFive", lastFive);
        payload.put("receivedAt", receivedAt.toString());
        payload.put("transitions", List.of(
                OrderLogWriter.transition("PAYMENT", payment.getId(), PaymentStatus.UNPAID, PaymentStatus.SUBMITTED),
                OrderLogWriter.transition("SUBMISSION", submission.getId(), null, SubmissionStatus.SUBMITTED)));
        orderLogWriter.write(locked, "PAYMENT_PROOF_SUBMITTED", new Actor(role, actor.userId()), payload);
        Map<String, Object> params = Map.of("amount", payment.getAmount(), "paymentId", payment.getId().toString());
        if (OrderCommandSupport.payee(payment) == ActorRole.SITTER) {
            notifier.sitter(order, "PAYMENT_PROOF_SUBMITTED", false, params);
        } else {
            notifier.owner(order, "PAYMENT_PROOF_SUBMITTED", false, params);
        }
        OrderCommandResponses.Submitted resp = new OrderCommandResponses.Submitted(submission.getId(), payment.getId(),
                PaymentStatus.SUBMITTED.name(), receivedAt);
        support.complete(claim, 201, resp);
        return resp;
    }

    /** 交易 B 失敗（SD-ORDER §7.3）：仍持有 token 且無 submission 引用該物件鍵才 best-effort 刪除。 */
    private void cleanupAfterFailure(IdempotencyClaim claim, String objectKey, RuntimeException e) {
        IdempotencyFailOutcome outcome;
        try {
            int status;
            ErrorResponse body;
            if (e instanceof OrderException oe) {
                status = oe.getCode().status().value();
                body = ApiErrors.build(oe.getCode().name(), oe.getCode().defaultMessage(), oe.getPayload());
            } else if (e instanceof IdempotencyException ie) {
                status = ie.getKind().code().status().value();
                body = ApiErrors.build(ie.getKind().code().name(), ie.getKind().code().defaultMessage(), null);
            } else {
                status = 500;
                body = null;
            }
            outcome = idempotencyService.fail(claim, status, body);
        } catch (RuntimeException failError) {
            log.error("idempotency fail-stage error for record {}", claim.recordId(), failError);
            return;
        }
        boolean held = outcome == IdempotencyFailOutcome.DELETED || outcome == IdempotencyFailOutcome.RECORDED;
        if (held && !submissionRepository.existsByProofObjectKey(objectKey)) {
            try {
                mediaStorageService.deleteObject(objectKey);
            } catch (RuntimeException deleteError) {
                log.warn("payment proof cleanup failed, left for C10: {}", objectKey);
            }
        }
    }
}
