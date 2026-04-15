package com.catsitter.api.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
@org.springframework.context.annotation.Profile("smoke")
public class SmokeDataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public SmokeDataSeeder(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        System.out.println("Cleaning and Seeding Smoke Data via raw JDBC (Final Stability Pass)...");

        // 1. Cleanup - Reverse order of FK constraints
        String[] tables = {
            "SUBSCRIPTION_CHANGE_LOGS", "PAYMENT_TRANSACTIONS", "SITTER_SUBSCRIPTIONS", "SUBSCRIPTION_PLANS",
            "VISIT_SERVICES", "VISIT_MEDIA", "VISITS", "ORDER_ANSWERS", "ORDER_ACTION_LOGS",
            "ORDERS", "SITTER_QUESTION_OPTIONS", "SITTER_QUESTIONS", "SITTER_TRUST_CIRCLES",
            "SITTER_CLIENT_WHITELISTS", "SERVICES", "PETS", "PROFILES", "ACCOUNTS"
        };
        
        for (String table : tables) {
            try {
                jdbcTemplate.execute("DELETE FROM " + table);
            } catch (Exception e) {
                // Silently skip if table doesn't exist yet
            }
        }

        String encodedPassword = passwordEncoder.encode("password123");
        Timestamp now = Timestamp.from(Instant.now());

        // 2. Subscription Plans
        UUID freePlanId = UUID.randomUUID();
        UUID standardPlanId = UUID.randomUUID();
        UUID proPlanId = UUID.randomUUID();
        UUID premiumPlanId = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO SUBSCRIPTION_PLANS (ID, NAME, PLAN_CODE, ORDER_LIMIT, MONTHLY_PRICE, YEARLY_PRICE, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            freePlanId, "免費版", "FREE", 3, 0.0, 0.0, true, now, now);
        jdbcTemplate.update("INSERT INTO SUBSCRIPTION_PLANS (ID, NAME, PLAN_CODE, ORDER_LIMIT, MONTHLY_PRICE, YEARLY_PRICE, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            standardPlanId, "基礎版", "STANDARD", 20, 499.0, 4990.0, true, now, now);
        jdbcTemplate.update("INSERT INTO SUBSCRIPTION_PLANS (ID, NAME, PLAN_CODE, ORDER_LIMIT, MONTHLY_PRICE, YEARLY_PRICE, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            proPlanId, "專業版", "PRO", 999, 899.0, 8990.0, true, now, now);
        jdbcTemplate.update("INSERT INTO SUBSCRIPTION_PLANS (ID, NAME, PLAN_CODE, ORDER_LIMIT, MONTHLY_PRICE, YEARLY_PRICE, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            premiumPlanId, "頂級版", "PREMIUM", 1999, 1299.0, 12990.0, true, now, now);

        // 3. Accounts
        UUID sitterAccId = UUID.fromString("efefefef-0000-0000-0000-000000000001");
        jdbcTemplate.update("INSERT INTO ACCOUNTS (ID, EMAIL, PASSWORD_HASH, OAUTH_PROVIDER, STATUS, LAST_ACTIVE_ROLE, IS_EMAIL_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            sitterAccId, "sitter_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", "SITTER", true, now, now);

        UUID buddyAccId = UUID.fromString("efefefef-0000-0000-0000-000000000003");
        jdbcTemplate.update("INSERT INTO ACCOUNTS (ID, EMAIL, PASSWORD_HASH, OAUTH_PROVIDER, STATUS, LAST_ACTIVE_ROLE, IS_EMAIL_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            buddyAccId, "buddy_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", "SITTER", true, now, now);

        UUID newbieAccId = UUID.fromString("efefefef-0000-0000-0000-000000000004");
        jdbcTemplate.update("INSERT INTO ACCOUNTS (ID, EMAIL, PASSWORD_HASH, OAUTH_PROVIDER, STATUS, LAST_ACTIVE_ROLE, IS_EMAIL_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            newbieAccId, "newbie_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", null, false, now, now);

        UUID clientAccId = UUID.fromString("efefefef-0000-0000-0000-000000000002");
        jdbcTemplate.update("INSERT INTO ACCOUNTS (ID, EMAIL, PASSWORD_HASH, OAUTH_PROVIDER, STATUS, LAST_ACTIVE_ROLE, IS_EMAIL_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            clientAccId, "client_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", "CLIENT", true, now, now);

        // 4. Profiles
        UUID sitterProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000011");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, ID_CARD_FRONT_URL, FACE_PHOTO_URL, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            sitterProfileId, sitterAccId, "SITTER", "Sophia (Smoke Test)", "sophia-smoke", true, "identity/sophia-front.jpg", "identity/sophia-face.jpg", now, now);

        UUID buddyProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000013");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            buddyProfileId, buddyAccId, "SITTER", "Buddy Sitter", "buddy-sitter", true, now, now);

        UUID clientProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000031");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            clientProfileId, clientAccId, "CLIENT", "James Wilson (Smoke)", "james-smoke", false, now, now);

        // 5. Active Subscription for Sophia
        jdbcTemplate.update("INSERT INTO SITTER_SUBSCRIPTIONS (ID, SITTER_PROFILE_ID, PLAN_ID, START_DATE, END_DATE, STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, proPlanId, LocalDate.now(), LocalDate.now().plusDays(30), "ACTIVE", now, now);

        // 6. Pet — named "Fluffy" to match E2E spec selector /Fluffy|貓咪/
        UUID petId = UUID.fromString("efefefef-0000-0000-0000-000000000021");
        jdbcTemplate.update("INSERT INTO PETS (ID, CLIENT_PROFILE_ID, NAME, SPECIES, GENDER, WEIGHT_KG, NEUTERED_STATUS, VACCINATION_STATUS, DEWORMING_STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            petId, clientProfileId, "Fluffy", "CAT", "MALE", 5.5, "YES", "YES", "NO", now, now);

        UUID oliverId = UUID.fromString("efefefef-0000-0000-0000-000000000022");
        jdbcTemplate.update("INSERT INTO PETS (ID, CLIENT_PROFILE_ID, NAME, SPECIES, GENDER, WEIGHT_KG, NEUTERED_STATUS, VACCINATION_STATUS, DEWORMING_STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            oliverId, clientProfileId, "Oliver", "CAT", "MALE", 5.0, "YES", "YES", "NO", now, now);

        // 7. Service
        UUID serviceId = UUID.fromString("68511200-0045-6120-0000-000000000001");
        jdbcTemplate.update("INSERT INTO SERVICES (ID, SITTER_PROFILE_ID, NAME, BASE_PRICE, DURATION_MINUTES, SUPPORTED_PET_TYPES, SORT_ORDER, DESCRIPTION, IS_ACTIVE, IS_WHITELIST_ONLY, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ? FORMAT JSON, ?, ?, ?, ?, ?, ?)",
            serviceId, sitterProfileId, "照護服務", 500.0, 30, "[\"CAT\"]", 0, "這是專業的喵咪照護服務，包含餵食、清砂盆與陪玩。", true, false, now, now);

        // 8. Order + Visit
        UUID orderId = UUID.fromString("efefefef-0000-0000-0000-000000000030");
        jdbcTemplate.update("INSERT INTO ORDERS (ID, CLIENT_PROFILE_ID, CURRENT_SITTER_ID, SERVICE_ID, BASE_AMOUNT, SURCHARGE_AMOUNT, DISCOUNT_AMOUNT, TOTAL_AMOUNT, ORDER_STATUS, PAYMENT_STATUS, QUESTIONNAIRE_STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            orderId, clientProfileId, sitterProfileId, serviceId, 500.0, 0.0, 0.0, 500.0, "CONFIRMED", "PAID", "COMPLETED", now, now);

        UUID visitId = UUID.fromString("efefefef-0000-0000-0000-000000000040");
        Timestamp visitStart = Timestamp.from(Instant.now());
        Timestamp visitEnd   = Timestamp.from(Instant.now().plusSeconds(3600));
        jdbcTemplate.update("INSERT INTO VISITS (ID, ORDER_ID, VISIT_START_TIME, VISIT_END_TIME, STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?)",
            visitId, orderId, visitStart, visitEnd, "SCHEDULED", now, now);

        UUID visitServiceId = UUID.fromString("efefefef-0000-0000-0000-000000000050");
        jdbcTemplate.update("INSERT INTO VISIT_SERVICES (ID, VISIT_ID, PET_ID, SERVICE_TYPE, SORT_ORDER, IS_COMPLETED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            visitServiceId, visitId, oliverId, "FEEDING", 0, false, now, now);

        // 9. Sitter Questions
        jdbcTemplate.update("INSERT INTO SITTER_QUESTIONS (ID, SITTER_PROFILE_ID, TARGET_PET_TYPE, QUESTION_TEXT, QUESTION_TYPE, IS_REQUIRED, SORT_ORDER, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, "CAT", "飲食需求", "TEXT", true, 0, true, now, now);

        jdbcTemplate.update("INSERT INTO SITTER_QUESTIONS (ID, SITTER_PROFILE_ID, TARGET_PET_TYPE, QUESTION_TEXT, QUESTION_TYPE, IS_REQUIRED, SORT_ORDER, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, "CAT", "聯絡方式", "TEXT", true, 1, true, now, now);

        // 10. Whitelist
        jdbcTemplate.update("INSERT INTO SITTER_CLIENT_WHITELISTS (ID, SITTER_PROFILE_ID, CLIENT_PROFILE_ID, SKIP_QUESTIONNAIRE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, clientProfileId, true, now, now);

        // 11. Trust Circle
        jdbcTemplate.update("INSERT INTO SITTER_TRUST_CIRCLES (ID, OWNER_SITTER_ID, TRUSTED_SITTER_ID, STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, buddyProfileId, "ACTIVE", now, now);

        System.out.println("Smoke Data Seeded Successfully via Raw JDBC (Final Stability Pass)!");
    }
}
