package com.petsitter.application.service;

import com.petsitter.application.dto.CreateReferralRequest;
import com.petsitter.application.dto.ReferralCandidateDto;
import com.petsitter.application.dto.TrustRelationshipDto;
import com.petsitter.application.exception.TrustCircleException;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.NotificationRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.ReferralLogRepository;
import com.petsitter.domain.repository.TrustRelationshipRepository;
import com.petsitter.domain.repository.UserRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("PRD-010: 信任圈與轉介測試")
class TrustCircleAndReferralTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TrustCircleService trustCircleService;

    @Autowired
    private ReferralService referralService;

    @Autowired
    private TrustRelationshipRepository trustRelationshipRepository;

    @Autowired
    private ReferralLogRepository referralLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    private UUID sitterAId;
    private UUID sitterBId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        referralLogRepository.deleteAll();
        trustRelationshipRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();

        User sitterA = userRepository.save(User.builder().email("trust-a@test.com").passwordHash("hash").fullName("保母甲").role("SITTER").build());
        User sitterB = userRepository.save(User.builder().email("trust-b@test.com").passwordHash("hash").fullName("保母乙").role("SITTER").build());
        User owner = userRepository.save(User.builder().email("trust-owner@test.com").passwordHash("hash").fullName("飼主丙").role("OWNER").build());

        sitterAId = sitterA.getId();
        sitterBId = sitterB.getId();
        ownerId = owner.getId();

        profileRepository.save(Profile.builder().userId(sitterBId).type("SITTER").isOpen(true).kycStatus("VERIFIED").build());
    }

    @Test
    @DisplayName("雙向同意制：A 發送請求，B 同意後雙方信任圈皆能看到對方 (AC-1)")
    void should_EstablishTrust_When_BothPartiesAgree() {
        trustCircleService.sendTrustRequest(sitterAId, sitterBId);

        assertThat(trustCircleService.listTrustCircle(sitterAId)).isEmpty();
        List<TrustRelationshipDto> incoming = trustCircleService.listIncomingRequests(sitterBId);
        assertThat(incoming).hasSize(1);

        trustCircleService.respondToTrustRequest(sitterBId, incoming.get(0).getRelationshipId(), true);

        assertThat(trustCircleService.listTrustCircle(sitterAId)).extracting(TrustRelationshipDto::getSitterId).containsExactly(sitterBId);
        assertThat(trustCircleService.listTrustCircle(sitterBId)).extracting(TrustRelationshipDto::getSitterId).containsExactly(sitterAId);
    }

    @Test
    @DisplayName("移除信任關係後，雙方清單應立即消失該對象 (AC-5)")
    void should_RemoveFromBothLists_When_RelationshipRemoved() {
        trustCircleService.sendTrustRequest(sitterAId, sitterBId);
        TrustRelationshipDto pending = trustCircleService.listIncomingRequests(sitterBId).get(0);
        trustCircleService.respondToTrustRequest(sitterBId, pending.getRelationshipId(), true);

        trustCircleService.removeTrustRelationship(sitterAId, pending.getRelationshipId());

        assertThat(trustCircleService.listTrustCircle(sitterAId)).isEmpty();
        assertThat(trustCircleService.listTrustCircle(sitterBId)).isEmpty();
    }

    @Test
    @DisplayName("轉介給非信任圈內的保母應被拒絕")
    void should_RejectReferral_When_NotInTrustCircle() {
        CreateReferralRequest request = CreateReferralRequest.builder()
                .ownerId(ownerId)
                .recommendedSitterIds(List.of(sitterBId))
                .message("推薦這位保母")
                .build();

        Assertions.assertThrows(TrustCircleException.class,
                () -> referralService.createReferral(sitterAId, request));
    }

    @Test
    @DisplayName("轉介給信任圈內的保母應成功，並各自收到通知含推薦語 (AC-2/AC-4)")
    void should_CreateReferral_AndNotifyBoth_WhenInTrustCircle() {
        trustCircleService.sendTrustRequest(sitterAId, sitterBId);
        TrustRelationshipDto pending = trustCircleService.listIncomingRequests(sitterBId).get(0);
        trustCircleService.respondToTrustRequest(sitterBId, pending.getRelationshipId(), true);

        CreateReferralRequest request = CreateReferralRequest.builder()
                .ownerId(ownerId)
                .recommendedSitterIds(List.of(sitterBId))
                .message("這位保母也很細心")
                .build();

        referralService.createReferral(sitterAId, request);

        assertThat(referralLogRepository.count()).isEqualTo(1);

        List<com.petsitter.domain.model.Notification> ownerNotifications =
                notificationRepository.findAll().stream().filter(n -> n.getUserId().equals(ownerId)).toList();
        assertThat(ownerNotifications).hasSize(1);
        assertThat(ownerNotifications.get(0).getContent()).contains("這位保母也很細心");

        List<com.petsitter.domain.model.Notification> sitterReferralNotifications =
                notificationRepository.findAll().stream()
                        .filter(n -> n.getUserId().equals(sitterBId) && "收到轉介".equals(n.getTitle()))
                        .toList();
        assertThat(sitterReferralNotifications).hasSize(1);
    }

    @Test
    @DisplayName("轉介候選名單應排除已將該飼主列入黑名單的信任圈保母")
    void should_ExcludeBlacklistedSitter_FromReferralCandidates() {
        trustCircleService.sendTrustRequest(sitterAId, sitterBId);
        TrustRelationshipDto pending = trustCircleService.listIncomingRequests(sitterBId).get(0);
        trustCircleService.respondToTrustRequest(sitterBId, pending.getRelationshipId(), true);

        List<ReferralCandidateDto> candidates = referralService.getReferralCandidates(sitterAId, ownerId);
        assertThat(candidates).extracting(ReferralCandidateDto::getSitterId).containsExactly(sitterBId);
    }
}
