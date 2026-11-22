package com.petsitter.interfaces.controller.fulfill;

import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.dto.fulfill.VisitCareLogResponses;
import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.service.fulfill.VisitCareLogMediaService;
import com.petsitter.application.service.fulfill.VisitCareLogService;
import com.petsitter.application.service.order.OrderClock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 照護日誌端點 F13–F19（SD-FULFILL §6.2 / §6.3；PRD-022）。{@code receivedAt} 於 Controller 進入時以注入 Clock 取得一次
 * （§2.5 第 9 點）。媒體由瀏覽器以 F17 簽名網址直傳 GCS，不經後端。
 */
@RestController
@RequestMapping(VisitController.VISIT + "/care-log")
@RequiredArgsConstructor
public class VisitCareLogController {

    private final VisitCareLogService careLogService;
    private final VisitCareLogMediaService mediaService;
    private final OrderClock clock;

    /** F13 讀取日誌。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public VisitCareLogResponses.VisitCareLogView read(@PathVariable UUID orderId, @PathVariable UUID visitId) {
        return careLogService.read(orderId, visitId);
    }

    /** F14 暫存（整份覆寫）。 */
    @PutMapping("/draft")
    @PreAuthorize("hasRole('SITTER')")
    public VisitCareLogResponses.Draft draft(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                        @RequestBody(required = false) FulfillRequests.VisitCareLogText body) {
        OffsetDateTime receivedAt = clock.now();
        return careLogService.saveDraft(orderId, visitId, body, receivedAt);
    }

    /** F15 送出。 */
    @PostMapping("/submit")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public VisitCareLogResponses.Submitted submit(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                             @RequestHeader(value = VisitController.KEY, required = false) String key,
                                             @RequestBody(required = false) FulfillRequests.VisitCareLogText body) {
        OffsetDateTime receivedAt = clock.now();
        return careLogService.submit(orderId, visitId, body, receivedAt);
    }

    /** F16 申請上傳（預檢、建槽位）。 */
    @PostMapping("/media-uploads")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<VisitCareLogResponses.UploadSlot> requestUpload(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                                                    @RequestHeader(value = VisitController.KEY, required = false) String key,
                                                                    @RequestBody(required = false) FulfillRequests.MediaUpload body) {
        OffsetDateTime receivedAt = clock.now();
        return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.requestUpload(orderId, visitId, body, receivedAt));
    }

    /** F17 取得簽名上傳網址。 */
    @GetMapping("/media-uploads/{uploadId}/target")
    @PreAuthorize("hasRole('SITTER')")
    public VisitCareLogResponses.UploadTarget target(@PathVariable UUID orderId, @PathVariable UUID visitId, @PathVariable UUID uploadId) {
        return mediaService.target(orderId, visitId, uploadId);
    }

    /** F18 確認上傳（以 uploadId 天然冪等）。 */
    @PostMapping("/media-uploads/{uploadId}/complete")
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<VisitCareLogResponses.MediaCreated> complete(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                                                 @PathVariable UUID uploadId) {
        OffsetDateTime receivedAt = clock.now();
        return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.completeUpload(orderId, visitId, uploadId, receivedAt));
    }

    /** F19 刪除媒體（重送 404）。 */
    @DeleteMapping("/media/{mediaId}")
    @PreAuthorize("hasRole('SITTER')")
    public VisitCareLogResponses.MediaDeleted deleteMedia(@PathVariable UUID orderId, @PathVariable UUID visitId, @PathVariable UUID mediaId) {
        OffsetDateTime receivedAt = clock.now();
        return mediaService.deleteMedia(orderId, visitId, mediaId, receivedAt);
    }
}
