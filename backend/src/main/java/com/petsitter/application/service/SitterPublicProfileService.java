package com.petsitter.application.service;

import com.petsitter.application.dto.ForbiddenKeywordDto;
import com.petsitter.application.dto.PublicProfileResponse;
import com.petsitter.application.dto.UpdatePublicProfileRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

public interface SitterPublicProfileService {
    
    /**
     * 更新保母公開檔案與標籤
     */
    void updateProfile(UUID sitterId, UpdatePublicProfileRequest request);

    /**
     * 上傳保母大頭貼
     */
    String uploadAvatar(UUID sitterId, MultipartFile file);

    /**
     * 取得保母公開檔案 (支援 5 級 Gating 隱私篩選)
     */
    PublicProfileResponse getPublicProfile(UUID sitterId, Optional<UUID> currentUserIdOpt);

    /**
     * 分頁查詢敏感詞 (Admin)
     */
    Page<ForbiddenKeywordDto> getForbiddenKeywords(String q, Pageable pageable);

    /**
     * 新增敏感詞 (Admin)
     */
    ForbiddenKeywordDto addForbiddenKeyword(String keyword, UUID adminId);

    /**
     * 刪除敏感詞 (Admin)
     */
    void deleteForbiddenKeyword(UUID id, UUID adminId);
}
