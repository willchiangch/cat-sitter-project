package com.petsitter.application.service;

import com.petsitter.application.dto.ForbiddenKeywordDto;
import com.petsitter.application.dto.PublicProfileResponse;
import com.petsitter.application.dto.ServiceAreaDto;
import com.petsitter.application.dto.UpdatePublicProfileRequest;
import com.petsitter.application.exception.PublicProfileException;
import com.petsitter.domain.model.ForbiddenKeyword;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.SitterServiceArea;
import com.petsitter.domain.model.SitterTag;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.ForbiddenKeywordRepository;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.SitterServiceAreaRepository;
import com.petsitter.domain.repository.SitterTagRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SitterPublicProfileServiceImpl implements SitterPublicProfileService {

    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final SitterTagRepository sitterTagRepository;
    private final SitterServiceAreaRepository sitterServiceAreaRepository;
    private final ForbiddenKeywordRepository forbiddenKeywordRepository;
    private final MediaStorageService mediaStorageService;
    private final AuditLogService auditLogService;
    private final GatekeeperService gatekeeperService;

    @Autowired
    @Lazy
    private SitterPublicProfileServiceImpl self;

    private String cleanTextForComparison(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s\\p{Punct}\\p{P}]", "").toLowerCase();
    }

    @Override
    public void updateProfile(UUID sitterId, UpdatePublicProfileRequest request) {
        // 1. 敏感詞過濾 (在事務之外執行)
        List<ForbiddenKeyword> keywords = forbiddenKeywordRepository.findAll();
        String displayName = request.getDisplayName() != null ? request.getDisplayName() : "";
        String bio = request.getBio() != null ? request.getBio() : "";

        String cleanDisplayName = cleanTextForComparison(displayName);
        String cleanBio = cleanTextForComparison(bio);

        for (ForbiddenKeyword kw : keywords) {
            String word = cleanTextForComparison(kw.getKeyword());
            if (word.isEmpty()) continue;
            if (cleanDisplayName.contains(word) || cleanBio.contains(word)) {
                throw new PublicProfileException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "內容包含敏感詞彙");
            }
        }

        // 2. 呼叫 Transactional 方法執行 DB 覆寫
        try {
            self.txUpdateProfile(sitterId, request);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new PublicProfileException(HttpStatus.CONFLICT, "MSG_DATA_CONCURRENCY_CONFLICT", "內容已被更新，請重新整理後再試");
        }
    }

    @Transactional
    public void txUpdateProfile(UUID sitterId, UpdatePublicProfileRequest request) {
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new PublicProfileException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        // 樂觀鎖校驗
        if (request.getVersion() == null || !request.getVersion().equals(profile.getVersion())) {
            throw new PublicProfileException(HttpStatus.CONFLICT, "MSG_DATA_CONCURRENCY_CONFLICT", "內容已被更新，請重新整理後再試");
        }

        profile.setDisplayName(request.getDisplayName());
        profile.setBio(request.getBio());
        profile.setVisible(request.getIsVisible());

        // 覆寫 Sitter Tags (限制最多 10 個標籤)
        sitterTagRepository.deleteByProfileId(profile.getId());
        sitterTagRepository.flush(); // 強制執行 DELETE 以免後續 INSERT 違反唯一性約束
        if (request.getTags() != null) {
            if (request.getTags().size() > 10) {
                throw new PublicProfileException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "最多僅能設定 10 個標籤");
            }
            for (String tag : request.getTags()) {
                if (tag != null && !tag.trim().isEmpty()) {
                    if (tag.length() > 10) {
                        throw new PublicProfileException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "單一標籤最多 10 字");
                    }
                    SitterTag sitterTag = SitterTag.builder()
                            .profileId(profile.getId())
                            .tag(tag.trim())
                            .build();
                    sitterTagRepository.save(sitterTag);
                }
            }
        }

        // 覆寫 Service Areas
        sitterServiceAreaRepository.deleteByProfileId(profile.getId());
        sitterServiceAreaRepository.flush(); // 強制執行 DELETE 以免後續 INSERT 違反唯一性約束
        if (request.getServiceAreas() != null) {
            for (ServiceAreaDto areaDto : request.getServiceAreas()) {
                SitterServiceArea area = SitterServiceArea.builder()
                        .profileId(profile.getId())
                        .city(areaDto.getCity())
                        .district(areaDto.getDistrict())
                        .build();
                sitterServiceAreaRepository.save(area);
            }
        }

        profileRepository.save(profile);

        // 寫入審計日誌
        auditLogService.writeUserActionLog("SITTER_PROFILE_UPDATE", "UPDATE", sitterId, profile.getId(), "profiles");
    }

    @Override
    public String uploadAvatar(UUID sitterId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PublicProfileException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "大頭貼檔案不可為空");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new PublicProfileException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "大頭貼格式僅限 JPG/PNG 且大小需在 2MB 以內");
        }
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/jpg"))) {
            throw new PublicProfileException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_MEDIA", "大頭貼格式僅限 JPG/PNG 且大小需在 2MB 以內");
        }

        // 上傳檔案 (事務外)
        String fileUrl = mediaStorageService.uploadAvatar(sitterId, file);
        String avatarUrlWithVersion = fileUrl + "?v=" + System.currentTimeMillis();

        self.txSaveAvatar(sitterId, avatarUrlWithVersion);

        return avatarUrlWithVersion;
    }

    @Transactional
    public void txSaveAvatar(UUID sitterId, String avatarUrl) {
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new PublicProfileException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        profile.setAvatarUrl(avatarUrl);
        profileRepository.save(profile);

        // 寫入審計日誌
        auditLogService.writeUserActionLog("SITTER_AVATAR_UPDATE", "UPDATE", sitterId, profile.getId(), "profiles");
    }

    @Override
    @Transactional(readOnly = true)
    public PublicProfileResponse getPublicProfile(UUID sitterId, Optional<UUID> currentUserIdOpt) {
        Profile profile = profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .orElseThrow(() -> new PublicProfileException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        User user = userRepository.findById(sitterId)
                .orElseThrow(() -> new PublicProfileException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料"));

        if (user.isDeleted()) {
            throw new PublicProfileException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該保母資料");
        }

        // Gating 隱私模糊化邏輯
        boolean gated = false;
        boolean isSelf = currentUserIdOpt.isPresent() && currentUserIdOpt.get().equals(sitterId);
        if (!isSelf) {
            if ("SUSPENDED".equals(profile.getKycStatus())) {
                gated = true;
            } else if (!profile.isVisible()) {
                gated = true;
            } else if (currentUserIdOpt.isPresent()) {
                boolean blocked = gatekeeperService.isBlocked(sitterId, currentUserIdOpt.get(), null);
                if (blocked) {
                    gated = true;
                }
            }
        }

        if (gated) {
            return PublicProfileResponse.builder()
                    .gated(true)
                    .displayName("保母休息中")
                    .avatarUrl("")
                    .bio("")
                    .tags(Collections.emptyList())
                    .serviceAreas(Collections.emptyList())
                    .isOpen(false)
                    .build();
        }

        List<String> tags = sitterTagRepository.findByProfileId(profile.getId())
                .stream()
                .map(SitterTag::getTag)
                .collect(Collectors.toList());

        List<ServiceAreaDto> serviceAreas = sitterServiceAreaRepository.findByProfileId(profile.getId())
                .stream()
                .map(a -> ServiceAreaDto.builder().city(a.getCity()).district(a.getDistrict()).build())
                .collect(Collectors.toList());

        return PublicProfileResponse.builder()
                .gated(false)
                .sitterId(sitterId)
                .displayName(profile.getDisplayName())
                .avatarUrl(profile.getAvatarUrl())
                .bio(profile.getBio())
                .tags(tags)
                .serviceAreas(serviceAreas)
                .isOpen(profile.isOpen())
                .kycStatus(profile.getKycStatus())
                .version(profile.getVersion())
                .isVisible(profile.isVisible())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ForbiddenKeywordDto> getForbiddenKeywords(String q, Pageable pageable) {
        // 不把「未帶查詢字串」交給 SQL 的 :q IS NULL OR ... 去判斷：
        // Supabase Transaction Pooler 搭配 prepareThreshold=0 (無 server-side prepared
        // statement) 時，null 綁定參數在這個 LIKE/CONCAT 運算式裡會讓 PostgreSQL 型別推斷
        // 出錯，導致 "function lower(bytea) does not exist"。改成在 Java 端分流，
        // 讓 :q 這個綁定參數在有值時才會被送出，永遠不會是 null。
        Page<ForbiddenKeyword> page = (q == null || q.isBlank())
                ? forbiddenKeywordRepository.findAll(pageable)
                : forbiddenKeywordRepository.findByKeywordContaining(q, pageable);

        return page.map(f -> ForbiddenKeywordDto.builder()
                        .id(f.getId())
                        .keyword(f.getKeyword())
                        .createdBy(f.getCreatedBy())
                        .createdAt(f.getCreatedAt())
                        .build());
    }

    @Override
    @Transactional
    public ForbiddenKeywordDto addForbiddenKeyword(String keyword, UUID adminId) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new PublicProfileException(HttpStatus.BAD_REQUEST, "MSG_DATA_INVALID_INPUT", "關鍵字不可為空");
        }

        String cleanKeyword = keyword.trim();

        ForbiddenKeyword fk = ForbiddenKeyword.builder()
                .keyword(cleanKeyword)
                .createdBy(adminId)
                .build();

        ForbiddenKeyword saved;
        try {
            saved = forbiddenKeywordRepository.save(fk);
            forbiddenKeywordRepository.flush(); // 強制 flushing 以立即觸發資料庫約束驗證
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new PublicProfileException(HttpStatus.CONFLICT, "MSG_DATA_CONCURRENCY_CONFLICT", "此關鍵字已存在");
        }

        // 寫入審計日誌
        auditLogService.writeUserActionLog("ADMIN_FORBIDDEN_KEYWORD_ADD", "CREATE", adminId, saved.getId(), "forbidden_keywords");

        return ForbiddenKeywordDto.builder()
                .id(saved.getId())
                .keyword(saved.getKeyword())
                .createdBy(saved.getCreatedBy())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Override
    @Transactional
    public void deleteForbiddenKeyword(UUID id, UUID adminId) {
        ForbiddenKeyword fk = forbiddenKeywordRepository.findById(id)
                .orElseThrow(() -> new PublicProfileException(HttpStatus.NOT_FOUND, "MSG_DATA_F11", "找不到該關鍵字"));

        forbiddenKeywordRepository.delete(fk);

        // 寫入審計日誌
        auditLogService.writeUserActionLog("ADMIN_FORBIDDEN_KEYWORD_DELETE", "DELETE", adminId, id, "forbidden_keywords");
    }
}
