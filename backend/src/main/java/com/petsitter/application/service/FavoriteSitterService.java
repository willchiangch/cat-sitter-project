package com.petsitter.application.service;

import com.petsitter.application.dto.FavoriteSitterDto;
import com.petsitter.application.dto.SitterSearchResultDto;
import com.petsitter.application.exception.FavoriteSitterException;
import com.petsitter.domain.model.FavoriteSitter;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.SitterTag;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.FavoriteSitterRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.SitterTagRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PRD-019: 飼主我的最愛保母。收藏是飼主單方面的行為，刻意不發送任何通知給保母，
 * 也不提供任何「誰收藏了我」的查詢管道給保母 (資料規則「單向隱私規則」)。
 */
@Service
@RequiredArgsConstructor
public class FavoriteSitterService {

    private static final int MAX_FAVORITES_PER_OWNER = 50;

    private final FavoriteSitterRepository favoriteSitterRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final SitterTagRepository sitterTagRepository;

    @Transactional(readOnly = true)
    public List<FavoriteSitterDto> listFavorites(UUID ownerId) {
        return favoriteSitterRepository.findByOwnerIdAndIsDeletedFalseOrderByCreatedAtDesc(ownerId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public SitterSearchResultDto searchSitter(String query) {
        if (query == null || query.isBlank()) {
            throw new FavoriteSitterException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "請輸入保母 ID 或 Email");
        }

        Optional<User> found = parseAsUuid(query)
                .flatMap(userRepository::findById)
                .or(() -> userRepository.findByEmail(query));

        User user = found
                .filter(u -> "SITTER".equals(u.getRole()) && !u.isDeleted())
                .orElseThrow(() -> new FavoriteSitterException(HttpStatus.NOT_FOUND, "SITTER_NOT_FOUND", "查無此保母，請確認 ID 是否正確"));

        return SitterSearchResultDto.builder()
                .sitterId(user.getId())
                .displayName(user.getFullName())
                .email(user.getEmail())
                .build();
    }

    @Transactional
    public void addFavorite(UUID ownerId, UUID sitterId) {
        User sitter = userRepository.findById(sitterId)
                .filter(u -> "SITTER".equals(u.getRole()) && !u.isDeleted())
                .orElseThrow(() -> new FavoriteSitterException(HttpStatus.NOT_FOUND, "SITTER_NOT_FOUND", "查無此保母，請確認 ID 是否正確"));

        if (favoriteSitterRepository.findByOwnerIdAndSitterIdAndIsDeletedFalse(ownerId, sitterId).isPresent()) {
            return; // 已收藏過，直接視為成功 (冪等)
        }

        if (favoriteSitterRepository.countByOwnerIdAndIsDeletedFalse(ownerId) >= MAX_FAVORITES_PER_OWNER) {
            throw new FavoriteSitterException(HttpStatus.UNPROCESSABLE_ENTITY, "FAVORITE_LIMIT_EXCEEDED",
                    "最多只能收藏 " + MAX_FAVORITES_PER_OWNER + " 位保母");
        }

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new FavoriteSitterException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到使用者資料"));

        favoriteSitterRepository.save(FavoriteSitter.builder().owner(owner).sitter(sitter).build());
    }

    @Transactional
    public void removeFavorite(UUID ownerId, UUID sitterId) {
        FavoriteSitter favorite = favoriteSitterRepository.findByOwnerIdAndSitterIdAndIsDeletedFalse(ownerId, sitterId)
                .orElse(null);
        if (favorite == null) {
            return; // 已經不在收藏清單中，視為成功 (冪等)
        }
        favorite.setDeleted(true);
        favoriteSitterRepository.save(favorite);
    }

    private FavoriteSitterDto toDto(FavoriteSitter favorite) {
        User sitter = favorite.getSitter();
        if (sitter == null || sitter.isDeleted()) {
            return FavoriteSitterDto.builder()
                    .sitterId(favorite.getSitter() != null ? favorite.getSitter().getId() : null)
                    .removed(true)
                    .build();
        }

        Optional<Profile> profileOpt = profileRepository.findByUserIdAndType(sitter.getId(), "SITTER");
        boolean hidden = profileOpt.map(p -> !p.isOpen() || !p.isVisible()).orElse(true);
        List<String> tags = profileOpt
                .map(p -> sitterTagRepository.findByProfileId(p.getId()).stream().map(SitterTag::getTag).collect(Collectors.toList()))
                .orElse(List.of());

        return FavoriteSitterDto.builder()
                .sitterId(sitter.getId())
                .displayName(profileOpt.map(Profile::getDisplayName).orElse(sitter.getFullName()))
                .avatarUrl(profileOpt.map(Profile::getAvatarUrl).orElse(null))
                .tags(tags)
                .removed(false)
                .hidden(hidden)
                .build();
    }

    private Optional<UUID> parseAsUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
