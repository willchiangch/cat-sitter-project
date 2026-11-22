package com.petsitter.application.dto.fulfill;

import tools.jackson.databind.JsonNode;

/**
 * F5 / F9 / F11 / F12 / F14 / F15 / F16 / FT1 / FT2 request（SD-FULFILL §6.1 DTO 型別）：型別敏感欄位以 {@link JsonNode}
 * 接收，延後到 G6 由 {@code FulfillRequestValidator} 判型別（不掛 {@code @Valid}；進方法前不會因型別錯誤 400）。
 */
public final class FulfillRequests {

    private FulfillRequests() {
    }

    public record Rename(JsonNode name) {
    }

    public record AddItem(JsonNode title) {
    }

    public record Reorder(JsonNode itemIds) {
    }

    public record Check(JsonNode checked) {
    }

    public record VisitCareLogText(JsonNode text) {
    }

    public record MediaUpload(JsonNode file, JsonNode caption) {
    }

    /** FT1。 */
    public record ForceStatus(String status, String startedAt, String completedAt, String completionSource,
                              String closedAt, String cancelledAt, Boolean careLogSubmitted) {
    }

    /** FT2。 */
    public record ShiftTime(String target, Long minutes, Long days) {
    }
}
