package com.catsitter.api.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
@Profile("smoke")
public class SmokeDataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public SmokeDataSeeder(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Cleaning and Seeding Smoke Data with JdbcTemplate...");

        try {
            // Cleanup existing smoke records to ensure fresh state
            jdbcTemplate.update("DELETE FROM sitter_trust_circles WHERE owner_sitter_id IN ('efefefef-0000-0000-0000-000000000011', 'efefefef-0000-0000-0000-000000000012')");
            jdbcTemplate.update("DELETE FROM sitter_question_options WHERE question_id IN (SELECT id FROM sitter_questions WHERE sitter_profile_id = 'efefefef-0000-0000-0000-000000000011')");
            jdbcTemplate.update("DELETE FROM sitter_questions WHERE sitter_profile_id = 'efefefef-0000-0000-0000-000000000011'");
            jdbcTemplate.update("DELETE FROM order_answers WHERE order_id IN (SELECT id FROM orders WHERE id = 'efefefef-0000-0000-0000-000000000123')");
            jdbcTemplate.update("DELETE FROM order_action_logs WHERE order_id IN (SELECT id FROM orders WHERE id = 'efefefef-0000-0000-0000-000000000123')");
            jdbcTemplate.update("DELETE FROM visits WHERE order_id IN (SELECT id FROM orders WHERE id = 'efefefef-0000-0000-0000-000000000123')");
            jdbcTemplate.update("DELETE FROM orders WHERE id = 'efefefef-0000-0000-0000-000000000123'");
            jdbcTemplate.update("DELETE FROM sitter_client_whitelists WHERE sitter_profile_id = 'efefefef-0000-0000-0000-000000000011'");
            jdbcTemplate.update("DELETE FROM pets WHERE client_profile_id = 'efefefef-0000-0000-0000-000000000031'");
            jdbcTemplate.update("DELETE FROM services WHERE sitter_profile_id = 'efefefef-0000-0000-0000-000000000011'");
            jdbcTemplate.update("DELETE FROM profiles WHERE id IN ('efefefef-0000-0000-0000-000000000011', 'efefefef-0000-0000-0000-000000000012', 'efefefef-0000-0000-0000-000000000031')");
            jdbcTemplate.update("DELETE FROM accounts WHERE id IN ('efefefef-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000002', 'efefefef-0000-0000-0000-000000000003', 'efefefef-0000-0000-0000-000000000004')");

            String encodedPassword = passwordEncoder.encode("password123");
            Timestamp now = Timestamp.from(Instant.now());

            // 1. Create Sitter Account & Profile (Sophia)
            UUID sitterAccId = UUID.fromString("efefefef-0000-0000-0000-000000000001");
            jdbcTemplate.update("INSERT INTO accounts (id, email, password_hash, oauth_provider, status, last_active_role, is_email_verified, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sitterAccId, "sitter_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", "SITTER", true, now, now);

            UUID sitterProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000011");
            jdbcTemplate.update("INSERT INTO profiles (id, account_id, role_type, name, slug, is_verified, id_card_front_url, id_card_back_url, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sitterProfileId, sitterAccId, "SITTER", "Sophia (Smoke Test)", "sophia-smoke", true, "identity/sophia-front.jpg", "identity/sophia-back.jpg", now, now);

            // 1.1 Create another Sitter (Buddy for Trust Circle)
            UUID buddyAccId = UUID.fromString("efefefef-0000-0000-0000-000000000004");
            jdbcTemplate.update("INSERT INTO accounts (id, email, password_hash, oauth_provider, status, is_email_verified, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                buddyAccId, "buddy_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", true, now, now);

            UUID buddyProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000012");
            jdbcTemplate.update("INSERT INTO profiles (id, account_id, role_type, name, slug, is_verified, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                buddyProfileId, buddyAccId, "SITTER", "Buddy Sitter", "buddy-sitter", true, now, now);

            // 2. Create Client Account & Profile
            UUID clientAccId = UUID.fromString("efefefef-0000-0000-0000-000000000002");
            jdbcTemplate.update("INSERT INTO accounts (id, email, password_hash, oauth_provider, status, last_active_role, is_email_verified, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                clientAccId, "client_smoke@test.com", encodedPassword, "LOCAL", "ACTIVE", "CLIENT", true, now, now);

            UUID clientProfileId = UUID.fromString("efefefef-0000-0000-0000-000000000031");
            jdbcTemplate.update("INSERT INTO profiles (id, account_id, role_type, name, slug, is_verified, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                clientProfileId, clientAccId, "CLIENT", "James Wilson (Smoke)", "james-smoke", false, now, now);

            // 3. Create Pet
            UUID petId = UUID.fromString("efefefef-0000-0000-0000-000000000021");
            jdbcTemplate.update("INSERT INTO pets (id, client_profile_id, name, species, gender, weight_kg, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                petId, clientProfileId, "Oliver", "CAT", "MALE", 5.5, now, now);

            // 4. Create Service Packages
            UUID standardSvcId = UUID.fromString("efefefef-0000-0000-0000-000000000001");
            jdbcTemplate.update("INSERT INTO services (id, sitter_profile_id, name, base_price, duration_minutes, supported_pet_types, is_active, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                standardSvcId, sitterProfileId, "STANDARD", 500.0, 30, "[\"CAT\"]", true, 0, now, now);

            // 5. Create Sitter Questions
            UUID q1Id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO sitter_questions (id, sitter_profile_id, target_pet_type, question_text, question_type, is_required, sort_order, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                q1Id, sitterProfileId, "CAT", "貓咪的特殊飲食需求？", "TEXT", true, 0, true, now, now);

            UUID q2Id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO sitter_questions (id, sitter_profile_id, target_pet_type, question_text, question_type, is_required, sort_order, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                q2Id, sitterProfileId, "CAT", "緊急聯絡人的聯絡方式？", "TEXT", true, 1, true, now, now);

            // 6. Create Trust Circle Connection
            UUID trustId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO sitter_trust_circles (id, owner_sitter_id, trusted_sitter_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                trustId, sitterProfileId, buddyProfileId, "ACTIVE", now, now);

            // 7. Create a Mock Order
            UUID orderId = UUID.fromString("efefefef-0000-0000-0000-000000000123");
            jdbcTemplate.update("INSERT INTO orders (id, client_profile_id, current_sitter_id, service_id, service_name, service_unit_price, base_amount, total_amount, surcharge_amount, discount_amount, order_status, payment_status, questionnaire_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, clientProfileId, sitterProfileId, standardSvcId, "STANDARD_MOCK", 500.0, 1000.0, 1000.0, 0.0, 0.0, "PENDING", "UNPAID", "NOT_REQUIRED", now, now);

            System.out.println("Enhanced Smoke Data Seeded Successfully!");

            // 6. Whitelist James for Sophia (V31 skip questionnaire test)
            UUID whitelistId = UUID.fromString("efefefef-0000-0000-0000-000000000099");
            jdbcTemplate.update("INSERT INTO sitter_client_whitelists (id, sitter_profile_id, client_profile_id, skip_questionnaire, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                whitelistId, sitterProfileId, clientProfileId, true, now, now);

            System.out.println("Smoke Data Seeded Successfully via JdbcTemplate!");
        } catch (Exception e) {
            System.err.println("Seeding failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
