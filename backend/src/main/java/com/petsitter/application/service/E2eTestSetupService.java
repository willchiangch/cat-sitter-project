package com.petsitter.application.service;

import com.petsitter.application.dto.UpdateSitterPaymentInfoRequest;
import com.petsitter.application.dto.order.TestDataRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SD-ORDER §10.4 測試支援（provision-account 擴充、sitter-setup / owner-setup upsert）。只接受 {@code @e2e-journey.test}
 * 帳號。SD-IDENTITY / SUPPLY / BILLING / TRUST / NOTIFY / CARE 未重建前的過渡方法：以 SQL 寫入各自的表（§10.4「未重建模組以
 * 過渡方法實作」），收款帳號經既有 {@link PaymentService}（加密）。陣列項目以 (userId, key) 的 name-based UUID 作為 id，
 * 重複呼叫即逐項 upsert。
 */
@Service
@RequiredArgsConstructor
public class E2eTestSetupService {

    private static final Pattern TEST_EMAIL = Pattern.compile("^[^@\\s]+@e2e-journey\\.test$");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PaymentService paymentService;
    private final JsonMapper jsonMapper;

    public static UUID keyId(UUID userId, String kind, String key) {
        return UUID.nameUUIDFromBytes((userId + ":" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    public void requireTestUser(UUID userId) {
        String email = userId == null ? null : jdbc.query("SELECT email FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        if (email == null || !TEST_EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @Transactional
    public UUID provision(TestDataRequests.ProvisionAccount req) {
        if (req == null || req.email() == null || !TEST_EMAIL.matcher(req.email()).matches()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "僅允許建立 @e2e-journey.test 測試帳號");
        }
        if (req.password() == null || req.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password");
        }
        List<String> roles = req.roles() != null && !req.roles().isEmpty() ? req.roles()
                : req.role() != null ? List.of(req.role()) : List.of("OWNER");
        for (String r : roles) {
            if (!List.of("OWNER", "SITTER", "ADMIN").contains(r)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roles");
            }
        }
        String primary = roles.contains("ADMIN") ? "ADMIN" : roles.get(0);
        String name = req.displayName() != null ? req.displayName() : req.fullName();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, ?, ?, ?)",
                id, req.email(), passwordEncoder.encode(req.password()), name, primary);
        if (roles.contains("OWNER")) {
            jdbc.update("INSERT INTO profiles (id, user_id, type, kyc_status, display_name) VALUES (?, ?, 'CLIENT', 'UNVERIFIED', ?)",
                    UUID.randomUUID(), id, name);
        }
        if (roles.contains("SITTER") && !Boolean.FALSE.equals(req.sitterProfile())) {
            jdbc.update("INSERT INTO profiles (id, user_id, type, kyc_status, display_name) VALUES (?, ?, 'SITTER', 'UNVERIFIED', ?)",
                    UUID.randomUUID(), id, name);
        }
        return id;
    }

    @Transactional
    public Map<String, Object> sitterSetup(TestDataRequests.SitterSetup req) {
        UUID uid = req.userId();
        requireTestUser(uid);
        if (jdbc.queryForObject("SELECT count(*) FROM profiles WHERE user_id = ? AND type = 'SITTER'", Integer.class, uid) == 0) {
            jdbc.update("INSERT INTO profiles (id, user_id, type, kyc_status) VALUES (?, ?, 'SITTER', 'UNVERIFIED')", UUID.randomUUID(), uid);
        }
        if (req.tier() != null || req.subscriptionExpiresAt() != null) {
            if (jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE sitter_id = ?", Integer.class, uid) == 0) {
                jdbc.update("INSERT INTO subscriptions (id, sitter_id, plan_tier) VALUES (?, ?, 'FREE')", UUID.randomUUID(), uid);
            }
            if (req.tier() != null) {
                jdbc.update("UPDATE subscriptions SET plan_tier = ? WHERE sitter_id = ?", req.tier(), uid);
            }
            if (req.subscriptionExpiresAt() != null) {
                jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", req.subscriptionExpiresAt(), uid);
            }
        }
        if (req.kycStatus() != null) {
            jdbc.update("UPDATE profiles SET kyc_status = ? WHERE user_id = ? AND type = 'SITTER'", req.kycStatus(), uid);
        }
        if (req.suspended() != null) {
            jdbc.update("UPDATE profiles SET kyc_status = ? WHERE user_id = ? AND type = 'SITTER'",
                    req.suspended() ? "SUSPENDED" : (req.kycStatus() != null ? req.kycStatus() : "VERIFIED"), uid);
        }
        if (req.profileVisible() != null) {
            jdbc.update("UPDATE profiles SET is_visible = ? WHERE user_id = ? AND type = 'SITTER'", req.profileVisible(), uid);
        }
        if (Boolean.TRUE.equals(req.clearPayoutAccount())) {
            jdbc.update("UPDATE profiles SET bank_account_info = NULL WHERE user_id = ? AND type = 'SITTER'", uid);
        } else if (req.payoutAccount() != null) {
            TestDataRequests.PayoutAccount a = req.payoutAccount();
            paymentService.updateSitterPaymentInfo(uid, new UpdateSitterPaymentInfoRequest(a.bankCode(), a.branch(), a.accountNumber(), a.accountName()));
        }
        List<Map<String, Object>> plans = new ArrayList<>();
        for (TestDataRequests.Plan p : req.plans() == null ? List.<TestDataRequests.Plan>of() : req.plans()) {
            UUID pid = keyId(uid, "plan", p.key());
            if (jdbc.queryForObject("SELECT count(*) FROM service_plans WHERE id = ?", Integer.class, pid) == 0) {
                jdbc.update("INSERT INTO service_plans (id, sitter_id, name, price) VALUES (?, ?, ?, ?)", pid, uid,
                        p.title() == null ? p.key() : p.title(), p.unitPrice() == null ? 0 : p.unitPrice());
            }
            if (p.title() != null) {
                jdbc.update("UPDATE service_plans SET name = ? WHERE id = ?", p.title(), pid);
            }
            if (p.unitPrice() != null) {
                jdbc.update("UPDATE service_plans SET price = ? WHERE id = ?", p.unitPrice(), pid);
            }
            if (p.petTypes() != null) {
                jdbc.update("UPDATE service_plans SET applicable_pet_types = ?::jsonb WHERE id = ?", jsonMapper.writeValueAsString(p.petTypes()), pid);
            }
            if (p.defaultTasks() != null) {
                jdbc.update("UPDATE service_plans SET default_tasks = ?::jsonb WHERE id = ?", jsonMapper.writeValueAsString(p.defaultTasks()), pid);
            }
            if (p.bookableFrom() != null || p.bookableTo() != null) {
                jdbc.update("UPDATE service_plans SET start_date = ?, end_date = ? WHERE id = ?", p.bookableFrom(), p.bookableTo(), pid);
            }
            if (p.active() != null) {
                jdbc.update("UPDATE service_plans SET is_active = ? WHERE id = ?", p.active(), pid);
            }
            plans.add(Map.of("key", p.key(), "planId", pid));
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        for (TestDataRequests.Question q : req.questions() == null ? List.<TestDataRequests.Question>of() : req.questions()) {
            UUID qid = keyId(uid, "question", q.key());
            if (jdbc.queryForObject("SELECT count(*) FROM sitter_questions WHERE id = ?", Integer.class, qid) == 0) {
                jdbc.update("INSERT INTO sitter_questions (id, sitter_id, question_text, answer_type) VALUES (?, ?, ?, ?)", qid, uid,
                        q.text() == null ? q.key() : q.text(), q.type() == null ? "INPUT" : q.type());
            }
            if (q.text() != null) {
                jdbc.update("UPDATE sitter_questions SET question_text = ? WHERE id = ?", q.text(), qid);
            }
            if (q.type() != null) {
                jdbc.update("UPDATE sitter_questions SET answer_type = ? WHERE id = ?", q.type(), qid);
            }
            if (q.options() != null) {
                jdbc.update("UPDATE sitter_questions SET options = ?::jsonb WHERE id = ?", jsonMapper.writeValueAsString(q.options()), qid);
            }
            if (q.required() != null) {
                jdbc.update("UPDATE sitter_questions SET required = ? WHERE id = ?", q.required(), qid);
            }
            if (q.active() != null) {
                jdbc.update("UPDATE sitter_questions SET is_active = ? WHERE id = ?", q.active(), qid);
            }
            if (q.sortOrder() != null) {
                jdbc.update("UPDATE sitter_questions SET sort_order = ? WHERE id = ?", q.sortOrder(), qid);
            }
            questions.add(Map.of("key", q.key(), "questionId", qid));
        }
        if (req.gatekeeper() != null) {
            TestDataRequests.Gatekeeper g = req.gatekeeper();
            jdbc.update("DELETE FROM gatekeeper_rules WHERE sitter_id = ?", uid);
            for (UUID t : g.globalBlacklist() == null ? List.<UUID>of() : g.globalBlacklist()) {
                rule(uid, "BLACK", "GLOBAL", null, t);
            }
            for (UUID t : g.globalWhitelist() == null ? List.<UUID>of() : g.globalWhitelist()) {
                rule(uid, "WHITE", "GLOBAL", null, t);
            }
            for (TestDataRequests.PlanRules pr : g.plans() == null ? List.<TestDataRequests.PlanRules>of() : g.plans()) {
                UUID pid = keyId(uid, "plan", pr.planKey());
                for (UUID t : pr.blacklist() == null ? List.<UUID>of() : pr.blacklist()) {
                    rule(uid, "BLACK", "PLAN", pid, t);
                }
                for (UUID t : pr.whitelist() == null ? List.<UUID>of() : pr.whitelist()) {
                    rule(uid, "WHITE", "PLAN", pid, t);
                }
            }
        }
        emailPreferences(uid, req.emailPreferences());
        for (UUID partner : req.trustCirclePartnerIds() == null ? List.<UUID>of() : req.trustCirclePartnerIds()) {
            jdbc.update("INSERT INTO trust_relationships (id, requester_id, target_id, status) VALUES (?, ?, ?, 'ACCEPTED') "
                    + "ON CONFLICT (requester_id, target_id) DO UPDATE SET status = 'ACCEPTED', is_deleted = false", UUID.randomUUID(), uid, partner);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plans", plans);
        out.put("questions", questions);
        return out;
    }

    private void rule(UUID sitter, String type, String scope, UUID planId, UUID target) {
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, plan_id, target_user_id) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sitter, type, scope, planId, target);
    }

    private void emailPreferences(UUID uid, Map<String, Boolean> prefs) {
        if (prefs == null) {
            return;
        }
        prefs.forEach((category, enabled) -> jdbc.update("""
                INSERT INTO notification_preferences (id, user_id, category, enable_in_app, enable_email) VALUES (?, ?, ?, true, ?)
                ON CONFLICT (user_id, category) DO UPDATE SET enable_email = EXCLUDED.enable_email, is_deleted = false
                """, UUID.randomUUID(), uid, category, enabled));
    }

    @Transactional
    public Map<String, Object> ownerSetup(TestDataRequests.OwnerSetup req) {
        UUID uid = req.userId();
        requireTestUser(uid);
        List<Map<String, Object>> pets = new ArrayList<>();
        for (TestDataRequests.Pet p : req.pets() == null ? List.<TestDataRequests.Pet>of() : req.pets()) {
            UUID pid = keyId(uid, "pet", p.key());
            if (jdbc.queryForObject("SELECT count(*) FROM pets WHERE id = ?", Integer.class, pid) == 0) {
                jdbc.update("INSERT INTO pets (id, owner_id, name, species) VALUES (?, ?, ?, ?)", pid, uid,
                        p.name() == null ? p.key() : p.name(), p.type() == null ? "CAT" : p.type());
            }
            if (p.name() != null) {
                jdbc.update("UPDATE pets SET name = ? WHERE id = ?", p.name(), pid);
            }
            if (p.type() != null) {
                jdbc.update("UPDATE pets SET species = ? WHERE id = ?", p.type(), pid);
            }
            if (p.deleted() != null) {
                jdbc.update("UPDATE pets SET is_deleted = ? WHERE id = ?", p.deleted(), pid);
            }
            pets.add(Map.of("key", p.key(), "petId", pid));
        }
        for (UUID s : req.favoriteSitterIds() == null ? List.<UUID>of() : req.favoriteSitterIds()) {
            favorite(uid, s);
        }
        List<UUID> fillers = new ArrayList<>();
        for (int i = 0; i < (req.favoriteFillerCount() == null ? 0 : req.favoriteFillerCount()); i++) {
            UUID f = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, 'x', ?, 'SITTER')",
                    f, "journey-filler-" + f + "@e2e-journey.test", "占位保母 " + (i + 1));
            favorite(uid, f);
            fillers.add(f);
        }
        emailPreferences(uid, req.emailPreferences());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pets", pets);
        out.put("fillerSitterIds", fillers);
        return out;
    }

    private void favorite(UUID owner, UUID sitter) {
        jdbc.update("INSERT INTO favorite_sitters (id, owner_id, sitter_id) VALUES (?, ?, ?) "
                + "ON CONFLICT (owner_id, sitter_id) DO UPDATE SET is_deleted = false", UUID.randomUUID(), owner, sitter);
    }
}
