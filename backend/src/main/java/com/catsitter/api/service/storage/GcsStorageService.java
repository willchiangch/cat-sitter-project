package com.catsitter.api.service.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "application.storage.type", havingValue = "GCS")
public class GcsStorageService implements StorageService {
    private static final Logger log = LoggerFactory.getLogger(GcsStorageService.class);


    private final Storage storage;

    public GcsStorageService(Storage storage) {
        this.storage = storage;
    }

    @Value("${application.storage.gcp.bucket-name}")
    private String bucketName;

    @Override
    public String store(MultipartFile file, String subFolder) throws IOException {
        String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
        String fullPath = subFolder + "/" + fileName;
        
        BlobId blobId = BlobId.of(bucketName, fullPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(file.getContentType())
                .build();
        
        storage.create(blobInfo, file.getBytes());
        log.info("Stored file in GCS: {}", fullPath);
        return fullPath;
    }

    @Override
    public void delete(String filePath) throws IOException {
        BlobId blobId = BlobId.of(bucketName, filePath);
        boolean deleted = storage.delete(blobId);
        if (!deleted) {
            log.warn("Failed to delete file from GCS: {}", filePath);
        }
    }

    @Override
    public String getUrl(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        // Serve via backend proxy — bucket stays private
        return "/api/v1/media/" + filePath;
    }

    @Override
    public Resource load(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        Blob blob = storage.get(BlobId.of(bucketName, filePath));
        if (blob == null || !blob.exists()) return null;
        return new ByteArrayResource(blob.getContent()) {
            @Override
            public String getFilename() { return filePath.substring(filePath.lastIndexOf('/') + 1); }
        };
    }

    @Override
    public String getSignedUrl(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;

        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, filePath)).build();

        // Generate a 15-minute signed URL for GET
        URL signedUrl = storage.signUrl(blobInfo, 15, TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature());

        return signedUrl.toString();
    }

    @Override
    public String generateUploadUrl(String fileName, String subFolder) {
        String uniqueName = UUID.randomUUID().toString() + "_" + fileName;
        String fullPath = subFolder + "/" + uniqueName;

        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, fullPath)).build();

        // Generate a 15-minute signed URL for PUT (Forced V4)
        URL signedUrl = storage.signUrl(blobInfo, 15, TimeUnit.MINUTES, 
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature());

        log.info("Generated signed URL for GCS upload: {}", fullPath);
        return signedUrl.toString();
    }
}
