package com.petsitter.infrastructure.storage;

import com.petsitter.application.service.MediaStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@Profile("local")
public class LocalMediaStorageServiceImpl implements MediaStorageService {

    @Value("${app.storage.local.dir:/tmp/cat_sitter_media}")
    private String localStorageDir;

    @Value("${app.storage.local.base-url:http://localhost:8080/local-media}")
    private String localBaseUrl;

    @Override
    public String uploadMedia(UUID sitterId, UUID ownerId, UUID mediaId, MultipartFile file) {
        try {
            String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
            String extension = StringUtils.getFilenameExtension(originalFilename);
            String targetFilename = mediaId.toString() + (extension != null ? "." + extension : "");
            
            // 路徑格式: /tmp/cat_sitter_media/{sitterId}_{ownerId}/{uuid}.ext
            String subDirName = sitterId.toString() + "_" + ownerId.toString();
            Path targetDirPath = Paths.get(localStorageDir, subDirName);
            
            if (!Files.exists(targetDirPath)) {
                Files.createDirectories(targetDirPath);
            }
            
            Path targetFilePath = targetDirPath.resolve(targetFilename);
            file.transferTo(targetFilePath);
            
            String fileUrl = localBaseUrl + "/" + subDirName + "/" + targetFilename;
            log.info("Local media uploaded: {}", fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("Failed to upload local media", e);
            throw new RuntimeException("Local media upload failed", e);
        }
    }

    @Override
    public String uploadReportMedia(String planTier, String date, java.util.UUID orderId, java.util.UUID mediaId, MultipartFile file) {
        try {
            String originalFilename = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
            String extension = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
            String targetFilename = mediaId.toString() + (extension != null ? "." + extension : "");
            
            // 路徑格式: /tmp/cat_sitter_media/{planTier}/{date}/{orderId}/{uuid}.ext
            String subPath = planTier.toLowerCase() + "/" + date + "/" + orderId.toString();
            Path targetDirPath = Paths.get(localStorageDir, planTier.toLowerCase(), date, orderId.toString());
            
            if (!Files.exists(targetDirPath)) {
                Files.createDirectories(targetDirPath);
            }
            
            Path targetFilePath = targetDirPath.resolve(targetFilename);
            file.transferTo(targetFilePath);
            
            String fileUrl = localBaseUrl + "/" + subPath + "/" + targetFilename;
            log.info("Local report media uploaded: {}", fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("Failed to upload local report media", e);
            throw new RuntimeException("Local report media upload failed", e);
        }
    }

    @Override
    public String uploadPetAvatar(UUID ownerId, UUID petId, MultipartFile file) {
        try {
            String originalFilename = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
            String extension = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
            String targetFilename = petId.toString() + (extension != null ? "." + extension : "");
            
            // 路徑格式: /tmp/cat_sitter_media/pet_avatars/{ownerId}/{petId}.ext
            String subDirName = "pet_avatars/" + ownerId.toString();
            Path targetDirPath = Paths.get(localStorageDir, subDirName);
            
            if (!Files.exists(targetDirPath)) {
                Files.createDirectories(targetDirPath);
            }
            
            Path targetFilePath = targetDirPath.resolve(targetFilename);
            file.transferTo(targetFilePath);
            
            String fileUrl = localBaseUrl + "/" + subDirName + "/" + targetFilename;
            log.info("Local pet avatar uploaded: {}", fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("Failed to upload local pet avatar", e);
            throw new RuntimeException("Local pet avatar upload failed", e);
        }
    }

    @Override
    public String uploadPaymentProof(UUID ownerId, UUID orderId, MultipartFile file) {
        try {
            String originalFilename = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
            String extension = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
            String dateStr = java.time.LocalDate.now().toString(); // YYYY-MM-DD
            String fileUuid = UUID.randomUUID().toString();
            String targetFilename = orderId.toString() + "_" + fileUuid + (extension != null ? "." + extension : "");
            
            // 路徑格式: /tmp/cat_sitter_media/payment-proofs/{date}/{orderId}_{fileUuid}.ext
            String subDirName = "payment-proofs/" + dateStr;
            Path targetDirPath = Paths.get(localStorageDir, subDirName);
            
            if (!Files.exists(targetDirPath)) {
                Files.createDirectories(targetDirPath);
            }
            
            Path targetFilePath = targetDirPath.resolve(targetFilename);
            file.transferTo(targetFilePath);
            
            String fileUrl = localBaseUrl + "/" + subDirName + "/" + targetFilename;
            log.info("Local payment proof uploaded: {}", fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("Failed to upload local payment proof", e);
            throw new RuntimeException("Local payment proof upload failed", e);
        }
    }

    @Override
    public String uploadKycFile(UUID sitterId, String type, MultipartFile file) {
        try {
            String originalFilename = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
            String extension = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
            String targetFilename = type + (extension != null ? "." + extension : ".jpg");
            
            String subDirName = "kyc/" + sitterId.toString();
            Path targetDirPath = Paths.get(localStorageDir, subDirName);
            
            if (!Files.exists(targetDirPath)) {
                Files.createDirectories(targetDirPath);
            }
            
            Path targetFilePath = targetDirPath.resolve(targetFilename);
            file.transferTo(targetFilePath);
            
            String objectKey = subDirName + "/" + targetFilename;
            log.info("Local KYC file uploaded: {}", objectKey);
            return objectKey;
        } catch (IOException e) {
            log.error("Failed to upload local KYC file", e);
            throw new RuntimeException("Local KYC file upload failed", e);
        }
    }

    @Override
    public String uploadAvatar(UUID sitterId, MultipartFile file) {
        try {
            String originalFilename = org.springframework.util.StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
            String extension = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
            String targetFilename = sitterId.toString() + (extension != null ? "." + extension : "");
            
            // 路徑格式: /tmp/cat_sitter_media/avatars/{sitterId}.ext
            String subDirName = "avatars";
            Path targetDirPath = Paths.get(localStorageDir, subDirName);
            
            if (!Files.exists(targetDirPath)) {
                Files.createDirectories(targetDirPath);
            }
            
            Path targetFilePath = targetDirPath.resolve(targetFilename);
            file.transferTo(targetFilePath);
            
            String fileUrl = localBaseUrl + "/" + subDirName + "/" + targetFilename;
            log.info("Local sitter avatar uploaded: {}", fileUrl);
            return fileUrl;
        } catch (IOException e) {
            log.error("Failed to upload local sitter avatar", e);
            throw new RuntimeException("Local sitter avatar upload failed", e);
        }
    }

    @Override
    public void deleteMedia(String mediaUrl) {

        if (!mediaUrl.startsWith(localBaseUrl)) {
            log.warn("Invalid local media URL: {}", mediaUrl);
            return;
        }
        
        try {
            String relativePath = mediaUrl.substring(localBaseUrl.length());
            Path targetPath = Paths.get(localStorageDir, relativePath);
            boolean deleted = Files.deleteIfExists(targetPath);
            if (deleted) {
                log.info("Local media deleted: {}", targetPath);
            } else {
                log.warn("Local media not found for deletion: {}", targetPath);
            }
        } catch (IOException e) {
            log.error("Failed to delete local media: {}", mediaUrl, e);
            throw new RuntimeException("Local media deletion failed", e);
        }
    }

    @Override
    public String generateSignedUrl(String objectKey, java.time.Duration ttl) {
        log.info("Local generating signed URL for objectKey {} with TTL {}", objectKey, ttl);
        // 本地環境下直接返回對應的可存取 URL，模擬 Signed URL 行為
        return localBaseUrl + "/" + objectKey;
    }
}
