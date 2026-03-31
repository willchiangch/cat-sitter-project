package com.catsitter.api.service.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface StorageService {
    /**
     * Store a file in the given sub-folder. Returns the relative file path.
     */
    String store(MultipartFile file, String subFolder) throws IOException;
    
    /**
     * Delete a file by its relative path.
     */
    void delete(String filePath) throws IOException;
    
    /**
     * Get the public URL for a file.
     */
    String getUrl(String filePath);

    /**
     * Get a signed URL for a file (short-lived access).
     */
    default String getSignedUrl(String filePath) {
        return getUrl(filePath); // Fallback to public URL for development
    }

    /**
     * Load a file as a Resource (for local serving).
     */
    default Resource load(String filePath) {
        return null;
    }

    /**
     * Generate a pre-signed URL for direct client-side upload (PUT).
     */
    default String generateUploadUrl(String fileName, String subFolder) {
        throw new UnsupportedOperationException("Signed URLs are not supported by this storage provider.");
    }
}
