package com.petsitter.application.service;

import com.petsitter.application.dto.FavoriteSitterDto;
import com.petsitter.application.dto.SitterSearchResultDto;
import com.petsitter.application.exception.FavoriteSitterException;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.FavoriteSitterRepository;
import com.petsitter.domain.repository.ProfileRepository;
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
@DisplayName("PRD-019: 我的最愛保母測試")
class FavoriteSitterServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private FavoriteSitterService favoriteSitterService;

    @Autowired
    private FavoriteSitterRepository favoriteSitterRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    private UUID ownerId;
    private UUID sitterId;

    @BeforeEach
    void setUp() {
        favoriteSitterRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();

        User owner = userRepository.save(User.builder().email("fav-owner@test.com").passwordHash("hash").fullName("收藏測試飼主").role("OWNER").build());
        User sitter = userRepository.save(User.builder().email("fav-sitter@test.com").passwordHash("hash").fullName("收藏測試保母").role("SITTER").build());
        ownerId = owner.getId();
        sitterId = sitter.getId();

        profileRepository.save(Profile.builder()
                .userId(sitterId).type("SITTER").displayName("收藏測試保母").isOpen(true).isVisible(true).build());
    }

    @Test
    @DisplayName("可依保母 ID 搜尋並加入收藏 (AC-1/AC-2)")
    void should_SearchAndAddFavorite_Successfully() {
        SitterSearchResultDto result = favoriteSitterService.searchSitter(sitterId.toString());
        assertThat(result.getSitterId()).isEqualTo(sitterId);

        favoriteSitterService.addFavorite(ownerId, sitterId);

        List<FavoriteSitterDto> favorites = favoriteSitterService.listFavorites(ownerId);
        assertThat(favorites).hasSize(1);
        assertThat(favorites.get(0).getSitterId()).isEqualTo(sitterId);
        assertThat(favorites.get(0).isHidden()).isFalse();
    }

    @Test
    @DisplayName("搜尋不存在的保母 ID 應提示查無此保母")
    void should_ThrowException_When_SitterNotFound() {
        Assertions.assertThrows(FavoriteSitterException.class,
                () -> favoriteSitterService.searchSitter(UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("保母切換為休息中/隱藏後，收藏清單應同步顯示 hidden (AC-3)")
    void should_MarkHidden_When_SitterClosedOrInvisible() {
        favoriteSitterService.addFavorite(ownerId, sitterId);

        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER").orElseThrow();
        profile.setOpen(false);
        profileRepository.save(profile);

        List<FavoriteSitterDto> favorites = favoriteSitterService.listFavorites(ownerId);
        assertThat(favorites.get(0).isHidden()).isTrue();
    }

    @Test
    @DisplayName("移除收藏後清單應消失該項目 (AC-5)")
    void should_RemoveFavorite_Successfully() {
        favoriteSitterService.addFavorite(ownerId, sitterId);
        assertThat(favoriteSitterService.listFavorites(ownerId)).hasSize(1);

        favoriteSitterService.removeFavorite(ownerId, sitterId);
        assertThat(favoriteSitterService.listFavorites(ownerId)).isEmpty();
    }

    @Test
    @DisplayName("超過收藏上限應拒絕新增")
    void should_RejectAddFavorite_When_ExceedsLimit() {
        for (int i = 0; i < 50; i++) {
            User extraSitter = userRepository.save(User.builder()
                    .email("extra-sitter-" + i + "@test.com").passwordHash("hash").fullName("保母" + i).role("SITTER").build());
            favoriteSitterService.addFavorite(ownerId, extraSitter.getId());
        }

        Assertions.assertThrows(FavoriteSitterException.class,
                () -> favoriteSitterService.addFavorite(ownerId, sitterId));
    }
}
