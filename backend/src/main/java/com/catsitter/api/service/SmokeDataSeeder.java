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
            "ORDERS", "SITTER_QUESTION_OPTIONS", "SITTER_QUESTIONS", "SITTER_CLIENT_WHITELISTS",
            "SERVICES", "PETS", "PROFILES", "ACCOUNTS"
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

        UUID clientAccId = UUID.fromString("efefefef-0000-0000-0000-000000000002");
        jdbcTemplate.update("INSERT INTO ACCOUNTS (ID, EMAIL, PASSWORD_HASH, OAUTH_PROVIDER, STATUS, LAST_ACTIVE_ROLE, IS_EMAIL_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            clientAccId, "client_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", "CLIENT", true, now, now);

        // 3. Profiles
        UUID sitterProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000011");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, ID_CARD_FRONT_URL, ID_CARD_BACK_URL, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            sitterProfileId, sitterAccId, "SITTER", "Sophia (Smoke Test)", "sophia-smoke", true, "identity/sophia-front.jpg", "identity/sophia-back.jpg", now, now);

        UUID clientProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000031");
        jdbcTemplate.update("INSERT INTO PROFILES (ID, ACCOUNT_ID, ROLE_TYPE, NAME, SLUG, IS_VERIFIED, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            clientProfileId, clientAccId, "CLIENT", "James Wilson (Smoke)", "james-smoke", false, now, now);

        // 4. Pet
        UUID petId = UUID.fromString("efefefef-0000-0000-0000-000000000021");
        jdbcTemplate.update("INSERT INTO PETS (ID, CLIENT_PROFILE_ID, NAME, SPECIES, GENDER, WEIGHT_KG, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            petId, clientProfileId, "Oliver", "CAT", "MALE", 5.5, now, now);

        // 5. Service (Careful with JSON format for H2 2.x)
        UUID serviceId = UUID.randomUUID();
        // H2 2.x prefers native JSON literals or explicit CAST if the driver doesn't handle the type.
        // We use FORMAT JSON to tell H2 that the string is already a JSON payload.
        String jsonPayload = "[\"CAT\"]";
        jdbcTemplate.update("INSERT INTO SERVICES (ID, SITTER_PROFILE_ID, NAME, BASE_PRICE, DURATION_MINUTES, SUPPORTED_PET_TYPES, SORT_ORDER, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ? FORMAT JSON, ?, ?, ?, ?)",
            serviceId, sitterProfileId, "STANDARD", 500.0, 30, jsonPayload, 0, true, now, now);

        // 6. Sitter Questions
        jdbcTemplate.update("INSERT INTO SITTER_QUESTIONS (ID, SITTER_PROFILE_ID, TARGET_PET_TYPE, QUESTION_TEXT, QUESTION_TYPE, IS_REQUIRED, SORT_ORDER, IS_ACTIVE, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), sitterProfileId, "CAT", "貓咪的特殊飲食需求？", "TEXT", true, 0, true, now, now);

        System.out.println("Smoke Data Seeded Successfully via Raw JDBC (Final Stability Pass)!");
    }
}
