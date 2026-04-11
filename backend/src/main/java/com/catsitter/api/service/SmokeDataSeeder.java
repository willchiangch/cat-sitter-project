package com.catsitter.api.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
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
            "VISIT_SERVICES", "VISIT_MEDIA", "VISITS", "ORDER_ANSWERS", "ORDER_ACTION_LOGS",
            "ORDERS", "SITTER_QUESTION_OPTIONS", "SITTER_QUESTIONS", "SITTER_TRUST_CIRCLES",
            "SITTER_CLIENT_WHITELISTS", "SERVICES", "PETS", "PROFILES", "ACCOUNTS"
        };
        
        for (String table : tables) {
            try {
                jdbcTemplate.execute("DELETE FROM " + table);
            } catch (Exception e) {
                System.out.println("Cleanup of " + table + " skipped: " + e.getMessage());
            }
        }

        String encodedPassword = passwordEncoder.encode("password123");
        Timestamp now = Timestamp.from(Instant.now());

        // 2. Accounts
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

        // 3. Profiles
        UUID sitterProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000011");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, ID_CARD_FRONT_URL, FACE_PHOTO_URL, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            sitterProfileId, sitterAccId, "SITTER", "Sophia (Smoke Test)", "sophia-smoke", true, "identity/sophia-front.jpg", "identity/sophia-face.jpg", now, now);

        UUID buddyProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000013");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            buddyProfileId, buddyAccId, "SITTER", "Buddy Sitter", "buddy-sitter", true, now, now);

        UUID clientProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000031");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            clientProfileId, clientAccId, "CLIENT", "James Wilson (Smoke)", "james-smoke", false, now, now);

        // 4. Pet — named "Fluffy" to match E2E spec selector /Fluffy|貓咪/
        UUID petId = UUID.fromString("efefefef-0000-0000-0000-000000000021");
        jdbcTemplate.update("INSERT INTO PETS (ID, CLIENT_PROFILE_ID, NAME, SPECIES, GENDER, WEIGHT_KG, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            petId, clientProfileId, "Fluffy", "CAT", "MALE", 5.5, now, now);

        // 4b. Oliver — matches sitter dashboard E2E test
        UUID oliverId = UUID.fromString("efefefef-0000-0000-0000-000000000022");
        jdbcTemplate.update("INSERT INTO PETS (ID, CLIENT_PROFILE_ID, NAME, SPECIES, GENDER, WEIGHT_KG, IS_NEUTERED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            oliverId, clientProfileId, "Oliver", "CAT", "MALE", 5.0, true, now, now);

        // 5. Service — fixed UUID to match BookingFlow.jsx hardcoded serviceId for STANDARD plan
        UUID serviceId = UUID.fromString("68511200-0045-6120-0000-000000000001");
        String jsonPayload = "[\"CAT\"]";
        jdbcTemplate.update("INSERT INTO SERVICES (ID, SITTER_PROFILE_ID, NAME, BASE_PRICE, DURATION_MINUTES, SUPPORTED_PET_TYPES, SORT_ORDER, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ? FORMAT JSON, ?, ?, ?, ?)",
            serviceId, sitterProfileId, "STANDARD", 500.0, 30, jsonPayload, 0, true, now, now);

        // 5b. Order + Visit for Oliver (matches sitter-business.spec.js dashboard test)
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

        // 6. Sitter Questions (match E2E expectations)
        jdbcTemplate.update("INSERT INTO SITTER_QUESTIONS (ID, SITTER_PROFILE_ID, TARGET_PET_TYPE, QUESTION_TEXT, QUESTION_TYPE, IS_REQUIRED, SORT_ORDER, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, "CAT", "飲食需求", "TEXT", true, 0, true, now, now);

        jdbcTemplate.update("INSERT INTO SITTER_QUESTIONS (ID, SITTER_PROFILE_ID, TARGET_PET_TYPE, QUESTION_TEXT, QUESTION_TYPE, IS_REQUIRED, SORT_ORDER, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, "CAT", "聯絡方式", "TEXT", true, 1, true, now, now);

        // 7a. Whitelist — James is VIP for Sophia (skip_questionnaire=true), matches booking-lifecycle test
        jdbcTemplate.update("INSERT INTO SITTER_CLIENT_WHITELISTS (ID, SITTER_PROFILE_ID, CLIENT_PROFILE_ID, SKIP_QUESTIONNAIRE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, clientProfileId, true, now, now);

        // 7. Trust Circle (match E2E expectations)
        jdbcTemplate.update("INSERT INTO SITTER_TRUST_CIRCLES (ID, OWNER_SITTER_ID, TRUSTED_SITTER_ID, STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, buddyProfileId, "ACTIVE", now, now);

        System.out.println("Smoke Data Seeded Successfully via Raw JDBC (Final Stability Pass)!");
    }
}
