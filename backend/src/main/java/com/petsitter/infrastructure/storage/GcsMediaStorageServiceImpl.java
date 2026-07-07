package com.petsitter.infrastructure.storage;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.Acl;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.petsitter.application.service.MediaStorageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Profile("!local")
public class GcsMediaStorageServiceImpl implements MediaStorageService {

    @Value("${gcp.storage.bucket-name:cat-sitter-media-bucket}")
    private String bucketName;

    @Value("${gcp.storage.signer-service-account:cat-sitter-runner@wd-pet-sitter.iam.gserviceaccount.com}")
    private String serviceAccountEmail;

    private Storage storage;
    private ServiceAccountSigner urlSigner;

    @PostConstruct
    void init() throws IOException {
        storage = StorageOptions.getDefaultInstance().getService();
        // Cloud Run 是 attached service account (無本地私鑰檔)，V4 signed URL 需要透過
        // IAM Credentials API 的 signBlob 動作簽名，因此用 ImpersonatedCredentials 自己模擬自己
        urlSigner = (ServiceAccountSigner) ImpersonatedCredentials.create(
                GoogleCredentials.getApplicationDefault(),
                serviceAccountEmail,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300
        );
    }

    private String buildTargetFilename(UUID mediaId, MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = StringUtils.getFilenameExtension(originalFilename);
        return mediaId.toString() + (extension != null ? "." + extension : "");
    }

    private String uploadPublic(String objectName, MultipartFile file) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .setAcl(List.of(Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER)))
                    .build();
            storage.create(blobInfo, file.getBytes());
            log.info("GCS uploaded (public): bucket={}, object={}", bucketName, objectName);
            return "https://storage.googleapis.com/" + bucketName + "/" + objectName;
        } catch (IOException e) {
            throw new RuntimeException("上傳檔案失敗", e);
        }
    }

    private String uploadPrivate(String objectName, MultipartFile file) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .build();
            storage.create(blobInfo, file.getBytes());
            log.info("GCS uploaded (private): bucket={}, object={}", bucketName, objectName);
            return objectName;
        } catch (IOException e) {
            throw new RuntimeException("上傳檔案失敗", e);
        }
    }

    @Override
    public String uploadMedia(UUID sitterId, UUID ownerId, UUID mediaId, MultipartFile file) {
        String objectName = "care_media/" + sitterId + "_" + ownerId + "/" + buildTargetFilename(mediaId, file);
        return uploadPublic(objectName, file);
    }

    @Override
    public String uploadReportMedia(String planTier, String date, UUID orderId, UUID mediaId, MultipartFile file) {
        String objectName = planTier.toLowerCase() + "/" + date + "/" + orderId + "/" + buildTargetFilename(mediaId, file);
        return uploadPublic(objectName, file);
    }

    @Override
    public String uploadPetAvatar(UUID ownerId, UUID petId, MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String targetFilename = petId + (extension != null ? "." + extension : "");
        String objectName = "pet-avatars/" + ownerId + "/" + targetFilename;
        return uploadPublic(objectName, file);
    }

    @Override
    public String uploadPaymentProof(UUID ownerId, UUID orderId, MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String dateStr = java.time.LocalDate.now().toString();
        String fileUuid = UUID.randomUUID().toString();
        String targetFilename = orderId + "_" + fileUuid + (extension != null ? "." + extension : "");
        String objectName = "payment-proofs/" + dateStr + "/" + targetFilename;
        return uploadPublic(objectName, file);
    }

    @Override
    public String uploadKycFile(UUID sitterId, String type, MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String targetFilename = type + (extension != null ? "." + extension : ".jpg");
        String objectKey = "kyc/" + sitterId + "/" + targetFilename;
        return uploadPrivate(objectKey, file);
    }

    @Override
    public String uploadAvatar(UUID sitterId, MultipartFile file) {
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.ext");
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String targetFilename = sitterId + (extension != null ? "." + extension : "");
        String objectName = "avatars/" + targetFilename;
        return uploadPublic(objectName, file);
    }

    @Override
    public void deleteMedia(String mediaUrl) {
        String prefix = "https://storage.googleapis.com/" + bucketName + "/";
        String objectName = mediaUrl.startsWith(prefix) ? mediaUrl.substring(prefix.length()) : mediaUrl;
        boolean deleted = storage.delete(BlobId.of(bucketName, objectName));
        log.info("GCS delete object={}, deleted={}", objectName, deleted);
    }

    @Override
    public String generateSignedUrl(String objectKey, Duration ttl) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectKey)).build();
        URL url = storage.signUrl(
                blobInfo,
                ttl.toMillis(),
                TimeUnit.MILLISECONDS,
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.signWith(urlSigner)
        );
        return url.toString();
    }
}