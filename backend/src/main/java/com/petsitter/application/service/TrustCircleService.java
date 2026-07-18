package com.petsitter.application.service;

import com.petsitter.application.dto.SitterSearchResultDto;
import com.petsitter.application.dto.TrustRelationshipDto;
import com.petsitter.application.exception.TrustCircleException;
import com.petsitter.domain.model.TrustRelationship;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.TrustRelationshipRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PRD-010 主流程 A：保母間的信任圈，須經雙方同意才成立 (雙向同意制)
 */
@Service
@RequiredArgsConstructor
public class TrustCircleService {

    private final TrustRelationshipRepository trustRelationshipRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public SitterSearchResultDto searchSitter(UUID excludingSelfId, String query) {
        if (query == null || query.isBlank()) {
            throw new TrustCircleException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "請輸入保母 ID 或 Email");
        }

        Optional<User> found = parseAsUuid(query)
                .flatMap(userRepository::findById)
                .or(() -> userRepository.findByEmail(query));

        User user = found
                .filter(u -> "SITTER".equals(u.getRole()) && !u.isDeleted() && !u.getId().equals(excludingSelfId))
                .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "SITTER_NOT_FOUND", "查無此保母，請確認 ID 是否正確"));

        return SitterSearchResultDto.builder()
                .sitterId(user.getId())
                .displayName(user.getFullName())
                .email(user.getEmail())
                .build();
    }

    private Optional<UUID> parseAsUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void sendTrustRequest(UUID requesterId, UUID targetId) {
        if (requesterId.equals(targetId)) {
            throw new TrustCircleException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "不可將自己加入信任圈");
        }

        User target = userRepository.findById(targetId)
                .filter(u -> "SITTER".equals(u.getRole()) && !u.isDeleted())
                .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "SITTER_NOT_FOUND", "查無此保母，請確認 ID 是否正確"));

        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到使用者資料"));

        trustRelationshipRepository.findBetween(requesterId, targetId).ifPresent(existing -> {
            throw new TrustCircleException(HttpStatus.CONFLICT, "TRUST_ALREADY_EXISTS", "已發送過請求或已是信任圈成員");
        });

        trustRelationshipRepository.save(TrustRelationship.builder()
                .requester(requester).target(target).status("PENDING").build());

        notificationService.createNotification(
                targetId, "信任圈邀請",
                requester.getFullName() + " 邀請您加入信任圈",
                "REFERRAL", null, "SITTER");
    }

    @Transactional
    public void respondToTrustRequest(UUID currentUserId, UUID relationshipId, boolean accept) {
        TrustRelationship relationship = trustRelationshipRepository.findById(relationshipId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "TRUST_NOT_FOUND", "找不到該信任請求"));

        if (!relationship.getTarget().getId().equals(currentUserId)) {
            throw new TrustCircleException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權操作此請求");
        }
        if (!"PENDING".equals(relationship.getStatus())) {
            throw new TrustCircleException(HttpStatus.CONFLICT, "STATE_CONFLICT", "此請求已被處理過");
        }

        if (accept) {
            relationship.setStatus("ACCEPTED");
            trustRelationshipRepository.save(relationship);
            notificationService.createNotification(
                    relationship.getRequester().getId(), "信任圈邀請已同意",
                    relationship.getTarget().getFullName() + " 同意加入您的信任圈",
                    "REFERRAL", null, "SITTER");
        } else {
            relationship.setDeleted(true);
            trustRelationshipRepository.save(relationship);
        }
    }

    @Transactional
    public void removeTrustRelationship(UUID currentUserId, UUID relationshipId) {
        TrustRelationship relationship = trustRelationshipRepository.findById(relationshipId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new TrustCircleException(HttpStatus.NOT_FOUND, "TRUST_NOT_FOUND", "找不到該信任關係"));

        boolean isParty = relationship.getRequester().getId().equals(currentUserId)
                || relationship.getTarget().getId().equals(currentUserId);
        if (!isParty) {
            throw new TrustCircleException(HttpStatus.FORBIDDEN, "FORBIDDEN", "無權操作此信任關係");
        }

        relationship.setDeleted(true);
        trustRelationshipRepository.save(relationship);
    }

    @Transactional(readOnly = true)
    public List<TrustRelationshipDto> listTrustCircle(UUID sitterId) {
        return trustRelationshipRepository.findAcceptedBySitterId(sitterId).stream()
                .map(r -> {
                    User other = r.getRequester().getId().equals(sitterId) ? r.getTarget() : r.getRequester();
                    return toDto(r, other);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TrustRelationshipDto> listIncomingRequests(UUID sitterId) {
        return trustRelationshipRepository.findByTargetIdAndStatusAndIsDeletedFalse(sitterId, "PENDING").stream()
                .map(r -> toDto(r, r.getRequester()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TrustRelationshipDto> listOutgoingRequests(UUID sitterId) {
        return trustRelationshipRepository.findByRequesterIdAndStatusAndIsDeletedFalse(sitterId, "PENDING").stream()
                .map(r -> toDto(r, r.getTarget()))
                .toList();
    }

    private TrustRelationshipDto toDto(TrustRelationship relationship, User other) {
        return TrustRelationshipDto.builder()
                .relationshipId(relationship.getId())
                .sitterId(other.getId())
                .displayName(other.getFullName())
                .email(other.getEmail())
                .status(relationship.getStatus())
                .build();
    }
}
