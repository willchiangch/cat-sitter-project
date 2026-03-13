-- ============================================================
-- V2: Schema Improvements
-- Based on review of V1 schema against business requirements
-- ============================================================

-- ==========================================
-- P0: Unique Constraints（防止重複資料）
-- ==========================================

-- 同帳號同角色只能有一個 profile
ALTER TABLE profiles
    ADD CONSTRAINT uq_profiles_account_role UNIQUE (account_id, role_type);

-- 飼主與保母的收藏關係不可重複
ALTER TABLE client_favorite_sitters
    ADD CONSTRAINT uq_client_favorite_sitters UNIQUE (client_profile_id, sitter_profile_id);

-- 信任圈關係不可重複
ALTER TABLE sitter_trust_circles
    ADD CONSTRAINT uq_sitter_trust_circles UNIQUE (owner_sitter_id, trusted_sitter_id);

-- 同一訂單同一題目只能有一個答案
ALTER TABLE order_answers
    ADD CONSTRAINT uq_order_answers_order_question UNIQUE (order_id, question_id);

-- 第三方登入帳號不可重複綁定
ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_oauth UNIQUE (oauth_provider, oauth_id);

-- ==========================================
-- P0: new_status 改為 nullable
-- 因為 ORDER_CREATED / TASK_COMPLETED 等 action_type 並不代表狀態轉移
-- ==========================================

ALTER TABLE order_action_logs
    ALTER COLUMN new_status DROP NOT NULL;

-- ==========================================
-- P0: 補上 FK Indexes（熱路徑查詢均需）
-- ==========================================

CREATE INDEX idx_profiles_account_id               ON profiles (account_id);
CREATE INDEX idx_services_sitter_profile_id        ON services (sitter_profile_id);
CREATE INDEX idx_sitter_questions_sitter_profile_id ON sitter_questions (sitter_profile_id);
CREATE INDEX idx_pets_client_profile_id            ON pets (client_profile_id);
CREATE INDEX idx_client_favorite_sitters_client    ON client_favorite_sitters (client_profile_id);
CREATE INDEX idx_client_favorite_sitters_sitter    ON client_favorite_sitters (sitter_profile_id);
CREATE INDEX idx_sitter_trust_circles_owner        ON sitter_trust_circles (owner_sitter_id);
CREATE INDEX idx_sitter_trust_circles_trusted      ON sitter_trust_circles (trusted_sitter_id);
CREATE INDEX idx_orders_client_profile_id          ON orders (client_profile_id);
CREATE INDEX idx_orders_current_sitter_id          ON orders (current_sitter_id);
CREATE INDEX idx_orders_service_id                 ON orders (service_id);
CREATE INDEX idx_orders_order_status               ON orders (order_status);
CREATE INDEX idx_order_answers_order_id            ON order_answers (order_id);
CREATE INDEX idx_order_answers_question_id         ON order_answers (question_id);
CREATE INDEX idx_order_transfers_order_id          ON order_transfers (order_id);
CREATE INDEX idx_order_transfers_from_sitter       ON order_transfers (from_sitter_id);
CREATE INDEX idx_order_transfers_to_sitter         ON order_transfers (to_sitter_id);
CREATE INDEX idx_visits_order_id                   ON visits (order_id);
CREATE INDEX idx_visit_tasks_visit_id              ON visit_tasks (visit_id);
CREATE INDEX idx_visit_tasks_pet_id                ON visit_tasks (pet_id);
CREATE INDEX idx_order_action_logs_order_id        ON order_action_logs (order_id);
CREATE INDEX idx_order_action_logs_visit_id        ON order_action_logs (visit_id);
CREATE INDEX idx_order_action_logs_actor           ON order_action_logs (actor_profile_id);

-- ==========================================
-- P1: visit_tasks 補 sort_order
-- 業務需求：任務可拖曳排序
-- ==========================================

ALTER TABLE visit_tasks
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

-- ==========================================
-- P1: services 補 sort_order
-- 業務需求：保母可排序服務方案
-- ==========================================

ALTER TABLE services
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

-- ==========================================
-- P1: pets 補缺失欄位
-- 業務需求：PetFormModal 需要性別、結紮、體重、大頭貼
-- ==========================================

ALTER TABLE pets
    ADD COLUMN gender      VARCHAR(10),    -- MALE / FEMALE / UNKNOWN
    ADD COLUMN is_neutered BOOLEAN,
    ADD COLUMN weight_kg   NUMERIC(5, 2),
    ADD COLUMN avatar_url  VARCHAR(1024);

-- ==========================================
-- P2: profiles 補開放預約期間欄位（SITTER 專用）
-- ==========================================

ALTER TABLE profiles
    ADD COLUMN booking_open_start DATE,
    ADD COLUMN booking_open_end   DATE;

-- ==========================================
-- P2: order_action_logs 補 metadata JSONB
-- 用途：記錄護照欄位異動 {"field": "medical_notes", "old": "...", "new": "..."}
-- ==========================================

ALTER TABLE order_action_logs
    ADD COLUMN metadata JSONB;

-- ==========================================
-- P2: orders 快照 service 方案資訊
-- 避免保母修改/下架方案後歷史訂單查不到原始資訊
-- ==========================================

ALTER TABLE orders
    ADD COLUMN service_name       VARCHAR(255),
    ADD COLUMN service_unit_price NUMERIC(10, 2);

COMMENT ON COLUMN orders.service_name       IS '訂單建立時快照的方案名稱，與 services.name 解耦';
COMMENT ON COLUMN orders.service_unit_price IS '訂單建立時快照的方案單價，與 services.base_price 解耦';
