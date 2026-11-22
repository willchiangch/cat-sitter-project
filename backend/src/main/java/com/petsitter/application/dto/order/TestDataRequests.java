package com.petsitter.application.dto.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SD-ORDER §10.4 測試支援端點的 request（只限 @e2e-journey.test 帳號）。 */
public final class TestDataRequests {

    private TestDataRequests() {
    }

    /** {@code fullName} / {@code role} 為舊欄位（向下相容，前端 journey 改寫後移除）。 */
    public record ProvisionAccount(String email, String password, String displayName, List<String> roles,
                                   Boolean sitterProfile, String fullName, String role) {
    }

    public record PayoutAccount(String bankCode, String branch, String accountNumber, String accountName) {
    }

    public record Plan(String key, String title, Integer unitPrice, List<String> petTypes, LocalDate bookableFrom,
                       LocalDate bookableTo, Boolean active, List<String> defaultTasks) {
    }

    public record Question(String key, String type, String text, List<String> options, Boolean required, Boolean active,
                           Integer sortOrder) {
    }

    public record PlanRules(String planKey, List<UUID> blacklist, List<UUID> whitelist) {
    }

    public record Gatekeeper(List<UUID> globalBlacklist, List<UUID> globalWhitelist, List<PlanRules> plans) {
    }

    /** 出現的欄位才覆寫；陣列依 key 逐項 upsert，未列出的既有項目不變。{@code payoutAccount} 明確給 null 需用 {@code clearPayoutAccount}。 */
    public record SitterSetup(UUID userId, String tier, OffsetDateTime subscriptionExpiresAt, Boolean suspended,
                              String kycStatus, Boolean profileVisible, PayoutAccount payoutAccount, Boolean clearPayoutAccount,
                              List<Plan> plans, List<Question> questions, Gatekeeper gatekeeper,
                              Map<String, Boolean> emailPreferences, List<UUID> trustCirclePartnerIds) {
    }

    public record Pet(String key, String name, String type, Boolean deleted) {
    }

    public record OwnerSetup(UUID userId, List<Pet> pets, List<UUID> favoriteSitterIds, Integer favoriteFillerCount,
                             Map<String, Boolean> emailPreferences) {
    }

    public record ShiftTime(String target, UUID paymentId, UUID submissionId, UUID modificationId, Integer minutes) {
    }
}
