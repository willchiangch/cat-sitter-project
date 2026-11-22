package com.petsitter.interfaces.controller;

import com.petsitter.infrastructure.storage.LocalSignedUploads;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;

/**
 * local profile 模擬 GCS 直傳（SD-FULFILL §8.2）：{@code PUT /local-storage/**}（不在 {@code /api/internal/} 下、permitAll），
 * 以網址內 HMAC + 到期時間驗證，並模擬 {@code Content-Type}、{@code x-goog-content-length-range}（宣告大小）、
 * {@code x-goog-if-generation-match: 0}（物件已存在回 412）。
 */
@RestController
@Profile("local")
@RequiredArgsConstructor
public class LocalStorageUploadController {

    private final LocalSignedUploads signedUploads;
    private final Clock clock;

    @Value("${app.storage.local.dir:/tmp/cat_sitter_media}")
    private String localStorageDir;

    @PutMapping(LocalSignedUploads.PATH_PREFIX + "**")
    public ResponseEntity<Void> put(HttpServletRequest request,
                                    @RequestParam("expires") long expires,
                                    @RequestParam("ct") String contentType,
                                    @RequestParam("len") long len,
                                    @RequestParam("sig") String sig,
                                    @RequestHeader(value = "Content-Type", required = false) String headerContentType,
                                    @RequestHeader(value = "x-goog-if-generation-match", required = false) String ifGenerationMatch)
            throws IOException {
        String objectKey = request.getRequestURI().substring(request.getRequestURI().indexOf(LocalSignedUploads.PATH_PREFIX)
                + LocalSignedUploads.PATH_PREFIX.length());
        Instant now = Instant.now(clock);
        if (!signedUploads.verify(objectKey, contentType, len, expires, sig, now) || !contentType.equals(headerContentType)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request.getContentLengthLong() != len) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Path root = Paths.get(localStorageDir).normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Files.createDirectories(target.getParent());
        if ("0".equals(ifGenerationMatch) && Files.exists(target)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes((int) Math.min(len + 1, Integer.MAX_VALUE));
            if (body.length != len) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            Files.write(target, body, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }
        return ResponseEntity.ok().build();
    }
}
