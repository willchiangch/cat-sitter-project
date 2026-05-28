package com.petsitter.infrastructure.storage;

import com.petsitter.application.service.MediaStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@Service
@Profile("!local")
public class GcsMediaStorageServiceImpl implements MediaStorageService {

    @Value("${gcp.storage.bucket-name:cat-sitter-media-bucket}")
    private String bucketName;

    @Override
    public String uploadMedia(UUID sitterId, UUID ownerId, UUID mediaId, MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String targetFilename = mediaId.toString() + (extension != null ? "." + extension : "");
        
        String objectName = "care_media/" + sitterId.toString() + "_" + ownerId.toString() + "/" + targetFilename;
        
        log.info("Mock GCS Upload to bucket {}, object {}", bucketName, objectName);
        
        // TODO: 實際呼叫 GCP SDK 進行上傳 (例如: storage.create(...))
        // 此處因為沒有引入 GCP SDK Dependency，先以 mock 形式回傳 URL
        
        return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
    }

    @Override
    public String uploadReportMedia(String planTier, String date, java.util.UUID orderId, java.util.UUID mediaId, MultipartFile file) {
        String originalFilename = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
        String targetFilename = mediaId.toString() + (extension != null ? "." + extension : "");
        
        // 格式: {planTier}/{date}/{orderId}/{uuid}.ext
        String objectName = planTier.toLowerCase() + "/" + date + "/" + orderId.toString() + "/" + targetFilename;
        
        log.info("Mock GCS Upload to bucket {}, object {}", bucketName, objectName);
        return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
    }

    @Override
    public String uploadPetAvatar(UUID ownerId, UUID petId, MultipartFile file) {
        String originalFilename = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
        String targetFilename = petId.toString() + (extension != null ? "." + extension : "");
        
        // 格式: pet-avatars/{ownerId}/{petId}.{ext}
        String objectName = "pet-avatars/" + ownerId.toString() + "/" + targetFilename;
        
        log.info("Mock GCS Upload pet avatar to bucket {}, object {}", bucketName, objectName);
        return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
    }

    @Override
    public void deleteMedia(String mediaUrl) {
        log.info("Mock GCS Delete object from URL: {}", mediaUrl);
        // TODO: 解析 mediaUrl 取得 objectName，然後呼叫 GCP SDK 刪除
    }
}
