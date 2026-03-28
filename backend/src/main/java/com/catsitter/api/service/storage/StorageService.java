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
     * Load a file as a Resource.
     */
    Resource load(String filePath);
}
