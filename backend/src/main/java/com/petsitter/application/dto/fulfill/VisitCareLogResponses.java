package com.petsitter.application.dto.fulfill;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** F13–F19 response（SD-FULFILL §6.3）。 */
public final class CareLogResponses {

    private CareLogResponses() {
    }

    public record Quota(String planTier, int imageLimit, long imageUsed, int videoLimit, long videoUsed) {
    }

    public record MediaItem(UUID mediaId, String mediaType, String caption, int sizeBytes, Integer durationSeconds,
                            OffsetDateTime uploadedAt, String url, OffsetDateTime urlExpiresAt, boolean purged) {
    }

    /** F13（SITTER 全欄；OWNER / ADMIN 只有 visitId / visitName / serviceDate / careLogId / text / submittedAt / media）。 */
    public record CareLogView(UUID visitId, String visitName, LocalDate serviceDate, UUID careLogId, String status,
                              String text, OffsetDateTime submittedAt, OffsetDateTime deadlineAt, Boolean expired,
                              Boolean editable, Boolean submittable, Quota quota, List<MediaItem> media) {
    }

    /** F14。 */
    public record Draft(UUID careLogId, String status, String text, OffsetDateTime updatedAt) {
    }

    /** F15。 */
    public record Submitted(UUID careLogId, String status, OffsetDateTime submittedAt) {
    }

    /** F16。 */
    public record UploadSlot(UUID uploadId, OffsetDateTime receivedAt, OffsetDateTime expiresAt) {
    }

    /** F17。 */
    public record UploadTarget(UUID uploadId, boolean alreadyCompleted, String uploadUrl, String method,
                               Map<String, String> headers, OffsetDateTime urlExpiresAt) {
    }

    /** F18（不含簽名網址）。 */
    public record MediaCreated(UUID mediaId, String mediaType, String caption, int sizeBytes, Integer durationSeconds,
                               OffsetDateTime uploadedAt, UUID careLogId, Quota quota) {
    }

    /** F19。 */
    public record MediaDeleted(UUID mediaId, Quota quota) {
    }
}
