package com.petsitter.application.service;

import com.petsitter.application.dto.CreateReferralRequest;
import com.petsitter.application.dto.ReferralCandidateDto;
import com.petsitter.application.exception.TrustCircleException;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.ReferralLog;
import com.petsitter.domain.model.TrustRelationship;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.ReferralLogRepository;
import com.petsitter.domain.repository.TrustRelationshipRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * PRD-010 主流程 B/C/D：轉介發起、通知與主動分享。
 * 轉介僅為資訊媒介，後續預約由飼主與被轉介保母重新建立合約 (PRD-010 資料規則)。
 */
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final TrustRelationshipRepository trustRelationshipRepository;
    private final ReferralLogRepository referralLogRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final GatekeeperService gatekeeperService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public List<ReferralCandidateDto> getReferralCandidates(UUID referringSitterId, UUID ownerId) {
        return trustRelationshipRepository.findAcceptedBySitterId(referringSitterId).stream()
                .map(r -> r.getRequester().getId().equals(referringSitterId) ? r.getTarget() : r.getRequester())
                // 黑名單前置過濾：已將該飼主列入黑名單的信任圈保母不顯示於可選清單 (PRD-010 主流程 B.2)
                .filter(candidate -> !gatekeeperService.isBlocked(candidate.getId(), ownerId, null))
                .map(candidate -> {
                    boolean available = profileRepository.findByUserIdAndType(candidate.getId(), "SITTER")
                            .map(p -> p.isOpen() && "VERIFIED".equals(p.getKycStatus()))
                            .orElse(false);
                    return ReferralCandidateDto.builder()
                            .sitterId(candidate.getId())
                            .displayName(candidate.getFullName())
                            .available(available)
                            .build();
                })
                .toList();
    }

    @Transactional
    public void createReferral(UUID referringSitterId, CreateReferralRequest request) {
        if (request.getRecommendedSitterIds() == null || request.getRecommendedSitterIds().isEmpty()) {
            throw new TrustCircleException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "請至少選擇一位推薦對象");
        }

        Order order = null;
        UUID ownerId;
        User referringSitter = userRepository.findById(referringSitterId)
                .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到使用者資料"));

        if (request.getOrderId() != null) {
            order = orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到訂單"));
            if (!order.getSitter().getId().equals(referringSitterId)) {
                throw new TrustCircleException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權操作此訂單");
            }
            ownerId = order.getOwner().getId();
        } else {
            if (request.getOwnerId() == null) {
                throw new TrustCircleException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "請指定要通知的飼主");
            }
            ownerId = request.getOwnerId();
        }

        User owner = userRepository.findById(ownerId)
                .filter(u -> "OWNER".equals(u.getRole()) && !u.isDeleted())
                .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到飼主資料"));

        for (UUID recommendedSitterId : request.getRecommendedSitterIds()) {
            TrustRelationship relationship = trustRelationshipRepository.findBetween(referringSitterId, recommendedSitterId)
                    .filter(r -> "ACCEPTED".equals(r.getStatus()))
                    .orElseThrow(() -> new TrustCircleException(HttpStatus.FORBIDDEN, "NOT_IN_TRUST_CIRCLE",
                            "只能轉介給信任圈內的保母，請先建立信任關係"));

            if (gatekeeperService.isBlocked(recommendedSitterId, ownerId, null)) {
                throw new TrustCircleException(HttpStatus.FORBIDDEN, "FORBIDDEN", "此保母已將該飼主列入黑名單，無法轉介");
            }

            User recommendedSitter = relationship.getRequester().getId().equals(recommendedSitterId)
                    ? relationship.getRequester() : relationship.getTarget();

            referralLogRepository.save(ReferralLog.builder()
                    .referringSitter(referringSitter)
                    .order(order)
                    .owner(owner)
                    .recommendedSitter(recommendedSitter)
                    .message(request.getMessage())
                    .build());

            // 對飼主：保母推薦通知 (AC-4 需完整呈現推薦語)
            notificationService.createNotification(
                    ownerId, "保母推薦",
                    referringSitter.getFullName() + " 推薦保母 " + recommendedSitter.getFullName()
                            + (request.getMessage() != null && !request.getMessage().isBlank() ? "：" + request.getMessage() : ""),
                    "REFERRAL", "/booking/" + recommendedSitter.getId(), "CLIENT");

            // 對被推薦保母：收到轉介通知
            notificationService.createNotification(
                    recommendedSitter.getId(), "收到轉介",
                    referringSitter.getFullName() + " 將飼主 " + owner.getFullName() + " 轉介給您",
                    "REFERRAL", null, "SITTER");
        }
    }
}
