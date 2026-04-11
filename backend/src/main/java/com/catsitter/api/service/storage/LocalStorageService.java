package com.catsitter.api.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "application.storage.type", havingValue = "LOCAL", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path rootPath;

    public LocalStorageService(@Value("${application.storage.local-path:./storage/media}") String localPath) {
        this.rootPath = Paths.get(localPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootPath);
            System.out.println("[STORAGE] Initialized at: " + this.rootPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage at " + this.rootPath, e);
        }
    }

    @Override
    public String store(MultipartFile file, String subFolder) throws IOException {
        String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
        Path targetDir = rootPath.resolve(subFolder);
        
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
            System.out.println("[STORAGE] Created subfolder: " + targetDir);
        }
        
        Path targetFile = targetDir.resolve(fileName);
        Files.copy(file.getInputStream(), targetFile);
        
        System.out.println("[STORAGE] Stored file: " + targetFile);
        return subFolder + "/" + fileName;
    }

    @Override
    public void delete(String filePath) throws IOException {
        Path targetFile = rootPath.resolve(filePath);
        Files.deleteIfExists(targetFile);
    }

    @Override
    public String getUrl(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        // 如果已經包含完整前綴或或是完整 URL，則直接回傳避免重複疊加
        if (filePath.startsWith("/api/v1/media/") || filePath.startsWith("http")) return filePath;
        // Point to the Local Media Controller
        return "/api/v1/media/" + filePath;
    }

    @Override
    public Resource load(String filePath) {
        Path targetFile = rootPath.resolve(filePath).normalize();
        System.out.println("[STORAGE] Loading file: " + targetFile.toAbsolutePath());
        if (Files.exists(targetFile)) {
            return new org.springframework.core.io.FileSystemResource(targetFile);
        }
        System.out.println("[STORAGE] File not found at path: " + targetFile.toAbsolutePath());
        return null;
    }
}
