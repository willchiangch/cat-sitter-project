package com.catsitter.api.smoke;

import com.catsitter.api.service.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("smoke")
public class StorageSmokeTest {

    @Autowired
    private StorageService storageService;

    @Test
    void testGenerateUploadUrl() {
        // This confirms that the GcsStorageService is active and can sign URLs 
        // using the local ADC (Application Default Credentials)
        String fileName = "test-check-connectivity.jpg";
        String subFolder = "services/test";
        
        String uploadUrl = storageService.generateUploadUrl(fileName, subFolder);
        
        System.out.println("Generated Signed URL: " + uploadUrl);

        assertNotNull(uploadUrl, "Upload URL should not be null");
        assertTrue(uploadUrl.contains("storage.googleapis.com"), "Upload URL should point to Google Cloud Storage");
        assertTrue(uploadUrl.toLowerCase().contains("x-goog-algorithm"), 
                "Upload URL should be a signed URL (V4). Found: " + uploadUrl);
    }
}
