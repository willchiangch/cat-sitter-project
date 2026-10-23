package com.petsitter.application.service;

import com.petsitter.domain.model.ModificationRequest;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.repository.ModificationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 判斷訂單目前的付款階段是「首次付款」還是「訂單變更補款」(PRD-016 C.3 / SD-016 §4.2)，
 * PaymentService（上傳/核帳/駁回）、OrderQueryServiceImpl（amountDue、憑證欄位）、
 * PaymentProofAccessService（簽哪一張憑證）共用同一個判斷，避免各自推論不一致。
 */
@Component
@RequiredArgsConstructor
public class SupplementPaymentResolver {

    private static final List<String> PAYMENT_STATUSES = List.of("PENDING_PAYMENT", "PAID");

    private final ModificationRequestRepository modificationRequestRepository;

    /**
     * 目前畫面/核帳應該看的那張憑證。supplement=true 代表屬於補款階段。
     */
    public record CurrentProof(String storedRef, String lastFive, boolean supplement) {
    }

    /**
     * 訂單處於付款狀態、且最新一筆變更請求是已確認 (M_DONE)、差額為正、尚未核帳的補款時回傳該請求。
     * 變更只能從 CONFIRMED/IN_PROGRESS 發起（首次付款之後），所以付款狀態下若有這樣的請求，
     * 這次付款必然是補款。
     */
    public Optional<ModificationRequest> findOpenSupplement(Order order) {
        if (!PAYMENT_STATUSES.contains(order.getStatus())) {
            return Optional.empty();
        }
        List<ModificationRequest> history = modificationRequestRepository.findByOrderIdOrderByCreatedAtDesc(order.getId());
        if (history.isEmpty()) {
            return Optional.empty();
        }
        ModificationRequest latest = history.get(0);
        boolean isOpenSupplement = "M_DONE".equals(latest.getStatus())
                && latest.getDiffAmount() != null
                && latest.getDiffAmount() > 0
                && latest.getSupplementVerifiedAt() == null;
        return isOpenSupplement ? Optional.of(latest) : Optional.empty();
    }

    public CurrentProof currentProof(Order order) {
        Optional<ModificationRequest> supplement = findOpenSupplement(order);
        if (supplement.isPresent()) {
            ModificationRequest mod = supplement.get();
            if (hasText(mod.getSupplementProofKey())) {
                return new CurrentProof(mod.getSupplementProofKey(), mod.getSupplementProofLastFive(), true);
            }
            if ("PENDING_PAYMENT".equals(order.getStatus())) {
                // 補款待上傳：還沒有本階段憑證，不能把首次付款的憑證當成補款憑證顯示
                return new CurrentProof(null, null, true);
            }
            // 2026-10 前的舊資料：補款憑證直接覆寫在 Order 欄位上，只能沿用
        }
        return new CurrentProof(order.getPaymentProofUrl(), order.getPaymentProofLastFive(), false);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
