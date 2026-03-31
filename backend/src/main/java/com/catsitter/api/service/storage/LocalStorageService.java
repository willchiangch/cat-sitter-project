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
        this.rootPath = Paths.get(localPath);
        try {
            Files.createDirectories(this.rootPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage", e);
        }
    }

    @Override
    public String store(MultipartFile file, String subFolder) throws IOException {
        String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
        Path targetDir = rootPath.resolve(subFolder);
        Files.createDirectories(targetDir);
        
        Path targetFile = targetDir.resolve(fileName);
        Files.copy(file.getInputStream(), targetFile);
        
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
        // Point to the Local Media Controller
        return "/api/v1/media/" + filePath;
    }

    @Override
    public Resource load(String filePath) {
        Path targetFile = rootPath.resolve(filePath);
        if (Files.exists(targetFile)) {
            return new FileSystemResource(targetFile);
        }
        return null;
    }
}
