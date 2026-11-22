package com.petsitter.application.dto.fulfill;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** F1–F12 response（SD-FULFILL §6.3）。null 欄位不輸出（GLOBAL §2.6）。 */
public final class VisitResponses {

    private VisitResponses() {
    }

    /** F1。 */
    public record VisitList(UUID orderId, String orderStatus, List<VisitRow> visits) {
    }

    /** F1 列 / F2 主體欄位。 */
    public record VisitRow(UUID visitId, UUID orderItemId, UUID planId, LocalDate serviceDate, int sequenceInDay,
                           int slotsInDay, String name, String customName, String status, OffsetDateTime startedAt,
                           OffsetDateTime completedAt, String completionSource, OffsetDateTime closedAt,
                           OffsetDateTime cancelledAt, OffsetDateTime zombieAt, CareLogSummary careLog,
                           ChecklistSummary checklist) {
    }

    /** SITTER：status / deadlineAt / expired；OWNER / ADMIN：只有 submitted（不洩漏草稿）。 */
    public record CareLogSummary(Boolean submitted, String status, OffsetDateTime deadlineAt, Boolean expired) {
    }

    public record ChecklistSummary(int total, int completed, List<ChecklistItem> items) {
    }

    /** {@code createdSource} 只在 F8 輸出。 */
    public record ChecklistItem(UUID itemId, String title, int sortOrder,
                                @JsonProperty("isCompleted") boolean isCompleted, OffsetDateTime checkedAt,
                                String createdSource) {
    }

    public record Party(UUID userId, String displayName) {
    }

    public record PetRef(UUID petId, String name) {
    }

    /** F2 服務面板：VisitRow 全部欄位 + owner / pets / allowedActions / dateReached / zombieDeadlinePassed。 */
    public record VisitDetail(UUID visitId, UUID orderId, String orderStatus, UUID orderItemId, UUID planId,
                              LocalDate serviceDate, int sequenceInDay, int slotsInDay, String name, String customName,
                              String status, OffsetDateTime startedAt, OffsetDateTime completedAt, String completionSource,
                              OffsetDateTime closedAt, OffsetDateTime cancelledAt, OffsetDateTime zombieAt,
                              CareLogSummary careLog, ChecklistSummary checklist, Party owner, List<PetRef> pets,
                              List<String> allowedActions, boolean dateReached, boolean zombieDeadlinePassed) {
    }

    /** F3。 */
    public record Started(UUID visitId, String status, OffsetDateTime startedAt, String orderStatus) {
    }

    /** F4。 */
    public record Completed(UUID visitId, String status, OffsetDateTime completedAt, String completionSource,
                            OffsetDateTime careLogDeadlineAt) {
    }

    /** F5。 */
    public record Renamed(UUID visitId, String name, String customName) {
    }

    /** F6 / F7 卡片。 */
    public record DashboardCard(UUID visitId, UUID orderId, LocalDate serviceDate, String name, String status,
                                String completionSource, OffsetDateTime zombieAt, boolean zombieDeadlinePassed,
                                Party counterpart, List<PetRef> pets) {
    }

    /** F6。 */
    public record SitterDashboard(List<DashboardCard> today, List<DashboardCard> overdue) {
    }

    /** F7。 */
    public record OwnerToday(List<DashboardCard> today) {
    }

    /** F8 / F10 / F11。 */
    public record Checklist(UUID visitId, List<ChecklistItem> items) {
    }

    /** F9。 */
    public record ItemCreated(UUID itemId, String title, int sortOrder, @JsonProperty("isCompleted") boolean isCompleted) {
    }

    /** F12。 */
    public record ItemChecked(UUID itemId, @JsonProperty("isCompleted") boolean isCompleted, OffsetDateTime checkedAt) {
    }
}
