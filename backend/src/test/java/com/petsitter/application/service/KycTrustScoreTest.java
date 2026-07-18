package com.petsitter.application.service;

import com.petsitter.application.dto.SitterTrustScoreDto;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.CareLogRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.UserRepository;
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
@DisplayName("PRD-020: 內部信用指標管理測試")
class KycTrustScoreTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private KycService kycService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private CareLogRepository careLogRepository;

    private UUID sitterId;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        careLogRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();

        User sitter = userRepository.save(User.builder()
                .email("trust-sitter@test.com").passwordHash("hash").fullName("信用測試保母").role("SITTER").build());
        User admin = userRepository.save(User.builder()
                .email("trust-admin@test.com").passwordHash("hash").fullName("管理員").role("ADMIN").build());
        sitterId = sitter.getId();
        adminId = admin.getId();

        profileRepository.save(Profile.builder()
                .userId(sitterId).type("SITTER").trustScore(100).kycStatus("VERIFIED").build());
    }

    @Test
    @DisplayName("管理員增減信用點數後，Profile 應正確更新且寫入稽核日誌")
    void should_AdjustTrustScore_AndWriteAuditLog() {
        kycService.adjustTrustScore(sitterId, adminId, -50, "訂單爭議判賠", UUID.randomUUID().toString());

        Profile updated = profileRepository.findByUserIdAndType(sitterId, "SITTER").orElseThrow();
        assertThat(updated.getTrustScore()).isEqualTo(50);
        assertThat(careLogRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("異動原因為空時應拒絕")
    void should_RejectAdjustTrustScore_When_ReasonBlank() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> kycService.adjustTrustScore(sitterId, adminId, -10, "  ", UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("低於門檻的保母應標註為高風險")
    void should_MarkHighRisk_When_ScoreBelowThreshold() {
        kycService.adjustTrustScore(sitterId, adminId, -45, "多次投訴", UUID.randomUUID().toString());

        List<SitterTrustScoreDto> list = kycService.listSitterTrustScores();
        SitterTrustScoreDto dto = list.stream().filter(d -> d.getSitterId().equals(sitterId)).findFirst().orElseThrow();

        assertThat(dto.getTrustScore()).isEqualTo(55);
        assertThat(dto.isHighRisk()).isTrue();
    }
}
