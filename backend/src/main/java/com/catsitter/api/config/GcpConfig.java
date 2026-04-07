package com.catsitter.api.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class GcpConfig {

    @Value("${application.storage.gcp.credentials-path}")
    private String credentialsPath;

    @Bean
    public Storage storage() throws IOException {
        StorageOptions.Builder optionsBuilder = StorageOptions.newBuilder();
        
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            java.io.File keyFile = new java.io.File(credentialsPath);
            if (keyFile.exists()) {
                optionsBuilder.setCredentials(GoogleCredentials.fromStream(new FileInputStream(credentialsPath)));
            } else {
                // 在雲端環境（如 Cloud Run）找不到路徑是正常的，會自動回退到 Default Credentials
                org.slf4j.LoggerFactory.getLogger(GcpConfig.class).info("GCP Key file not found at {}, using Default Credentials instead.", credentialsPath);
            }
        }
        
        return optionsBuilder.build().getService();
    }
}
