SET LOCAL lock_timeout = '10s';   -- Flyway 在同一交易內執行（SD-ORDER §5.1）

-- ============================================================
-- SD-ORDER §5.1.1 / SD-FULFILL §5.1：V20261122_01__order_rebuild.sql
-- 第 0–12 段照 SD-ORDER §5.1.1 原文；第 13–16 段照 SD-FULFILL §5.1 原文（接在第 12 段之後，
-- SD-ORDER 第 13 段只留指向，visits 欄位更名只在第 13 段出現一次）。
-- 一支到位、破壞性（使用者決定 2026-11-22）；forward fix only。
-- 本檔在同一次發布前仍會被修改：已在本機跑過 A 階段版本的 DB 需 flyway repair 或重建。
-- ============================================================

-- ============================================================
-- 0. 清空交易資料（baseline CQ-36）。明列所有表、不用 CASCADE：
--    若有遺漏的 FK 參照表，TRUNCATE 直接失敗，不會靜默連帶清空。
--    referral_logs 以 FK 參照 orders，一併清空（CQ-3 已決）。
-- ============================================================
TRUNCATE TABLE service_report_media, visit_service_reports, visits,
               referral_logs, modification_requests, order_logs,
               order_snapshots, orders;
DELETE FROM notifications WHERE category IN ('ORDER_AFFAIR', 'SERVICE_RECORD');   -- CQ-3 已決
UPDATE profiles SET bank_account_info = NULL WHERE bank_account_info IS NOT NULL;   -- CQ-20 已決：舊密文以公開預設金鑰加密，直接清空

-- ============================================================
-- 1. orders：DROP 舊欄位（相依的 idx_orders_payment_idempotency、idempotency_key UNIQUE 隨欄位刪除）
-- ============================================================
ALTER TABLE orders
    DROP COLUMN items, DROP COLUMN adjustment_amount, DROP COLUMN adjustment_reason,
    DROP COLUMN paid_at, DROP COLUMN payout_at, DROP COLUMN waiting_for_owner_action,
    DROP COLUMN is_disputed, DROP COLUMN idempotency_key, DROP COLUMN plan_id,
    DROP COLUMN payment_proof_url, DROP COLUMN payment_proof_last_five,
    DROP COLUMN disclaimer_agreed, DROP COLUMN disclaimer_agreed_at,
    DROP COLUMN payment_idempotency_key, DROP COLUMN questionnaire_answers,
    DROP COLUMN is_deleted,   -- 訂單不做軟刪除，新查詢不過濾；E2E 清理為硬刪除（§10.4）
    DROP COLUMN created_by, DROP COLUMN updated_by;   -- 操作者記在 order_logs；不用 @CreatedBy（SD-GLOBAL-SPEC §2.5）

ALTER TABLE orders
    ADD COLUMN base_amount                 INT NOT NULL,
    ADD COLUMN surcharge_amount            INT,
    ADD COLUMN discount_amount             INT,
    ADD COLUMN pricing_notes               VARCHAR(200),
    ADD COLUMN quoted_at                   TIMESTAMPTZ,
    ADD COLUMN confirmed_at                TIMESTAMPTZ,      -- O5；月接單額度計數依據
    ADD COLUMN review_expires_at           TIMESTAMPTZ NOT NULL,
    ADD COLUMN first_service_date          DATE NOT NULL,    -- 首日（Asia/Taipei 日期），O1 寫入後不變
    ADD COLUMN questionnaire_status        VARCHAR(16) NOT NULL,
    ADD COLUMN questionnaire_exempt_reason VARCHAR(24),
    ADD COLUMN cancelled_at                TIMESTAMPTZ,
    ADD COLUMN cancel_reason_code          VARCHAR(24),
    ADD COLUMN cancel_reason               VARCHAR(200),
    ADD COLUMN completed_by                VARCHAR(8),
    ADD COLUMN completion_basis_at         TIMESTAMPTZ,
    ADD COLUMN disputed_at                 TIMESTAMPTZ;
ALTER TABLE orders ALTER COLUMN total_amount DROP NOT NULL,   -- 報價前為 NULL
                   ALTER COLUMN total_amount DROP DEFAULT;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING','PENDING_PAYMENT','CONFIRMED','IN_PROGRESS','DISPUTED','COMPLETED','CANCELLED')),
    ADD CONSTRAINT ck_orders_base_amount CHECK (base_amount BETWEEN 0 AND 999999),
    ADD CONSTRAINT ck_orders_quote CHECK ((quoted_at IS NULL) = (total_amount IS NULL)),
    ADD CONSTRAINT ck_orders_total CHECK (total_amount IS NULL OR total_amount BETWEEN 0 AND 999999),
    ADD CONSTRAINT ck_orders_quote_parts CHECK (quoted_at IS NULL OR
        (surcharge_amount IS NOT NULL AND discount_amount IS NOT NULL
         AND surcharge_amount BETWEEN 0 AND 999999 AND discount_amount BETWEEN 0 AND 999999)),
    ADD CONSTRAINT ck_orders_pricing_notes CHECK (
        COALESCE(surcharge_amount, 0) + COALESCE(discount_amount, 0) = 0 OR pricing_notes IS NOT NULL),
    ADD CONSTRAINT ck_orders_questionnaire CHECK (
        (questionnaire_status = 'COMPLETED' AND questionnaire_exempt_reason IS NULL)
     OR (questionnaire_status = 'NOT_REQUIRED' AND questionnaire_exempt_reason IN ('REGULAR_OWNER','NO_ACTIVE_QUESTIONS'))),
    ADD CONSTRAINT ck_orders_cancelled CHECK (
        (status = 'CANCELLED') = (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND cancel_reason_code IS NOT NULL)),
    ADD CONSTRAINT ck_orders_cancelled_by CHECK (cancelled_by IS NULL OR cancelled_by IN ('OWNER','SITTER','SYSTEM','ADMIN')),
    ADD CONSTRAINT ck_orders_cancel_code CHECK (cancel_reason_code IS NULL OR cancel_reason_code IN
        ('OWNER_WITHDRAWN','SITTER_DECLINED','REVIEW_EXPIRED','SITTER_CANCELLED','PAYMENT_EXPIRED',
         'ADMIN_VOIDED','MODIFICATION','ADMIN_MEDIATED')),
    ADD CONSTRAINT ck_orders_completed CHECK (
        (status = 'COMPLETED') = (completed_at IS NOT NULL AND completed_by IS NOT NULL)),
    ADD CONSTRAINT ck_orders_completed_by CHECK (completed_by IS NULL OR completed_by IN ('OWNER','SYSTEM','ADMIN')),
    ADD CONSTRAINT ck_orders_confirmed CHECK (
        status NOT IN ('CONFIRMED','IN_PROGRESS','DISPUTED','COMPLETED') OR confirmed_at IS NOT NULL),
    ADD CONSTRAINT ck_orders_disputed CHECK (status <> 'DISPUTED' OR disputed_at IS NOT NULL);

-- O4 候選
CREATE INDEX ix_orders_review_expiry ON orders (review_expires_at) WHERE status = 'PENDING';
-- O12 候選（completion_basis_at 只在行程全部結束後非 NULL，§3.1.3）
CREATE INDEX ix_orders_auto_complete ON orders (completion_basis_at)
    WHERE status = 'IN_PROGRESS' AND completion_basis_at IS NOT NULL;
-- 月額度：保母當月進入 CONFIRMED 的筆數（取消不返還，confirmed_at 保留）
CREATE INDEX ix_orders_sitter_confirmed ON orders (sitter_id, confirmed_at) WHERE confirmed_at IS NOT NULL;
-- 熟客判斷
CREATE INDEX ix_orders_owner_sitter_completed ON orders (owner_id, sitter_id) WHERE status = 'COMPLETED';
-- 列表（雙方依狀態分頁、建立時間倒序）
CREATE INDEX ix_orders_owner_list  ON orders (owner_id, status, created_at DESC);
CREATE INDEX ix_orders_sitter_list ON orders (sitter_id, status, created_at DESC);
-- 帳務總覽（終態時間歸屬月份，CQ-8 已決）
CREATE INDEX ix_orders_sitter_terminal ON orders (sitter_id, COALESCE(completed_at, cancelled_at))
    WHERE status IN ('COMPLETED','CANCELLED');
-- 飼主歷史訂單（依終態時間排序 / 月份篩選，PRD-009 BR-23）
CREATE INDEX ix_orders_owner_terminal ON orders (owner_id, COALESCE(completed_at, cancelled_at))
    WHERE status IN ('COMPLETED','CANCELLED');

-- ============================================================
-- 2. modification_requests：DROP 舊欄位與舊索引，加新欄位（order_items、payments 會參照本表，先改）
-- ============================================================
DROP INDEX uk_active_modification;
DROP INDEX idx_mod_req_supplement_idempotency;
ALTER TABLE modification_requests
    DROP COLUMN previous_status, DROP COLUMN refund_proof_url, DROP COLUMN supplement_proof_key,
    DROP COLUMN supplement_proof_last_five, DROP COLUMN supplement_idempotency_key,
    DROP COLUMN supplement_submitted_at, DROP COLUMN supplement_verified_at,
    DROP COLUMN dates, DROP COLUMN payload, DROP COLUMN diff_amount, DROP COLUMN requested_by;
ALTER TABLE modification_requests ALTER COLUMN status DROP DEFAULT;
ALTER TABLE modification_requests DROP COLUMN is_deleted,   -- 變更不做軟刪除
                                  DROP COLUMN created_by, DROP COLUMN updated_by;   -- 發起者改記 initiated_by_user_id（SD-GLOBAL-SPEC §2.5）
ALTER TABLE modification_requests
    ADD COLUMN type                   VARCHAR(16) NOT NULL,
    ADD COLUMN initiated_by           VARCHAR(8)  NOT NULL,
    ADD COLUMN initiated_by_user_id   UUID REFERENCES users(id),
    ADD COLUMN reason                 VARCHAR(200) NOT NULL,
    ADD COLUMN requested_content      JSONB,       -- OWNER 期望內容
    ADD COLUMN quoted_content         JSONB,       -- SITTER 報價內容
    ADD COLUMN system_amount          INT,
    ADD COLUMN surcharge_amount       INT,
    ADD COLUMN discount_amount        INT,
    ADD COLUMN adjustment_reason      VARCHAR(200),
    -- new_total_amount 已存在（V20260717_01）
    ADD COLUMN quoted_net_paid        INT,
    ADD COLUMN quoted_diff            INT,
    ADD COLUMN agreed_net_paid        INT,
    ADD COLUMN agreed_diff            INT,
    ADD COLUMN quoted_at              TIMESTAMPTZ,
    ADD COLUMN agreed_at              TIMESTAMPTZ,
    ADD COLUMN expires_at             TIMESTAMPTZ,
    ADD COLUMN ended_at               TIMESTAMPTZ,
    ADD COLUMN reject_reason          VARCHAR(200),
    ADD COLUMN source_modification_id UUID REFERENCES modification_requests(id);
ALTER TABLE modification_requests
    ADD CONSTRAINT ck_modreq_status CHECK (status IN ('REQUESTED','QUOTED','AWAITING_SUPPLEMENT',
        'AWAITING_REFUND','SETTLED','REJECTED','WITHDRAWN','EXPIRED')),
    ADD CONSTRAINT ck_modreq_type CHECK (type IN ('CHANGE','CANCEL_ALL')),
    ADD CONSTRAINT ck_modreq_initiated_by CHECK (initiated_by IN ('OWNER','SITTER','SYSTEM')),
    ADD CONSTRAINT ck_modreq_system_user CHECK ((initiated_by = 'SYSTEM') = (initiated_by_user_id IS NULL)),
    ADD CONSTRAINT ck_modreq_system_source CHECK ((initiated_by = 'SYSTEM') = (source_modification_id IS NOT NULL)),
    ADD CONSTRAINT ck_modreq_expires CHECK (status NOT IN ('REQUESTED','QUOTED') OR expires_at IS NOT NULL),
    ADD CONSTRAINT ck_modreq_ended CHECK (
        (status IN ('SETTLED','REJECTED','WITHDRAWN','EXPIRED')) = (ended_at IS NOT NULL)),
    ADD CONSTRAINT ck_modreq_quoted CHECK (
        status = 'REQUESTED'
        OR (initiated_by = 'OWNER' AND status IN ('REJECTED','WITHDRAWN','EXPIRED'))
        OR new_total_amount IS NOT NULL),   -- 進入 QUOTED 之後必有新總額
    ADD CONSTRAINT ck_modreq_amounts CHECK (
        (new_total_amount IS NULL OR new_total_amount BETWEEN 0 AND 999999)
        AND (surcharge_amount IS NULL OR surcharge_amount BETWEEN 0 AND 999999)
        AND (discount_amount IS NULL OR discount_amount BETWEEN 0 AND 999999)
        AND (system_amount IS NULL OR system_amount >= 0)),
    ADD CONSTRAINT ck_modreq_adjustment_reason CHECK (
        COALESCE(surcharge_amount, 0) + COALESCE(discount_amount, 0) = 0 OR adjustment_reason IS NOT NULL);
-- 同訂單最多一筆未結束變更（baseline §3.4 可發起時機）
CREATE UNIQUE INDEX ux_modreq_open_per_order ON modification_requests (order_id)
    WHERE status IN ('REQUESTED', 'QUOTED', 'AWAITING_SUPPLEMENT', 'AWAITING_REFUND');
-- C5 候選
CREATE INDEX ix_modreq_expiry ON modification_requests (expires_at) WHERE status IN ('REQUESTED', 'QUOTED');
-- 歷史列表（created_at 倒序）
CREATE INDEX ix_modreq_order ON modification_requests (order_id, created_at DESC);

-- ============================================================
-- 3. order_items：預約群組（BOOKING）與變更新增項目（MODIFICATION），建立後不改
-- ============================================================
CREATE TABLE order_items (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                UUID NOT NULL REFERENCES orders(id),
    source                  VARCHAR(16) NOT NULL,
    group_index             SMALLINT,              -- BOOKING 的群組序（0 起）
    modification_request_id UUID REFERENCES modification_requests(id),
    plan_id                 UUID NOT NULL REFERENCES service_plans(id),
    service_dates           DATE[] NOT NULL,       -- Asia/Taipei 日期
    daily_count             SMALLINT NOT NULL,
    booked_unit_price       INT NOT NULL,          -- 送單時單價，只用於 base_amount；報價後以 order_plan_snapshots 為準
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_items_source CHECK (
        (source = 'BOOKING' AND group_index IS NOT NULL AND modification_request_id IS NULL)
     OR (source = 'MODIFICATION' AND group_index IS NULL AND modification_request_id IS NOT NULL)),
    CONSTRAINT ck_order_items_daily_count CHECK (daily_count BETWEEN 1 AND 3),
    CONSTRAINT ck_order_items_dates CHECK (cardinality(service_dates) BETWEEN 1 AND 60),
    CONSTRAINT ck_order_items_price CHECK (booked_unit_price BETWEEN 0 AND 999999)
);
CREATE INDEX ix_order_items_order ON order_items (order_id);
CREATE UNIQUE INDEX ux_order_items_group ON order_items (order_id, group_index) WHERE source = 'BOOKING';

-- ============================================================
-- 4. order_item_pets：群組 × 毛孩綁定（CQ-60）
-- ============================================================
CREATE TABLE order_item_pets (
    order_item_id UUID NOT NULL REFERENCES order_items(id),
    pet_id        UUID NOT NULL REFERENCES pets(id),   -- 毛孩為邏輯刪除，FK 不受影響
    PRIMARY KEY (order_item_id, pet_id)
);

-- ============================================================
-- 5. order_answers：問卷答案快照（O1 寫入後不變）
-- ============================================================
CREATE TABLE order_answers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID NOT NULL REFERENCES orders(id),
    question_id   UUID NOT NULL,          -- 不設 FK：題目可被保母刪除，快照獨立（PRD-005 BR-19）
    sort_order    SMALLINT NOT NULL,
    question_text VARCHAR(200) NOT NULL,
    question_type VARCHAR(16) NOT NULL,   -- 沿用 sitter_questions 的題型值
    answer        JSONB NOT NULL,         -- 選擇題存選項文字陣列；簡答 / 詳答存字串；未答存 JSON null
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_order_answers_question UNIQUE (order_id, question_id)
);

-- ============================================================
-- 6. order_plan_snapshots：報價時各方案單價與名稱快照（CQ-20）
-- ============================================================
CREATE TABLE order_plan_snapshots (
    order_id       UUID NOT NULL REFERENCES orders(id),
    plan_id        UUID NOT NULL REFERENCES service_plans(id),
    plan_title     VARCHAR(255) NOT NULL,
    unit_price     INT NOT NULL,
    snapshotted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (order_id, plan_id),
    CONSTRAINT ck_order_plan_snapshots_price CHECK (unit_price BETWEEN 0 AND 999999)
);

-- ============================================================
-- 7. order_snapshots：只留訂單層媒體額度快照（plan_tier、max_photos、max_videos、
--    media_retention_days，-1 = 永久，沿用 SD-CARE 語意）
-- ============================================================
ALTER TABLE order_snapshots
    DROP COLUMN snapshot_original_total, DROP COLUMN adjustment_amount, DROP COLUMN snapshot_data,
    DROP COLUMN max_video_seconds, DROP COLUMN snapshot_unit_price, DROP COLUMN snapshot_plan_title,
    DROP COLUMN is_deleted, DROP COLUMN created_by, DROP COLUMN updated_by;   -- 保留 version（展延保留期限會改寫）

-- ============================================================
-- 8. payments
-- ============================================================
CREATE TABLE payments (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                   UUID NOT NULL REFERENCES orders(id),
    modification_request_id    UUID REFERENCES modification_requests(id),
    source_payment_id          UUID REFERENCES payments(id),  -- ADMIN_ADJUSTMENT 補登被作廢的已匯出退款
    direction                  VARCHAR(8)  NOT NULL,
    purpose                    VARCHAR(24) NOT NULL,
    method                     VARCHAR(24) NOT NULL,
    amount                     INT NOT NULL,
    status                     VARCHAR(16) NOT NULL,
    due_at                     TIMESTAMPTZ,
    remind_at                  TIMESTAMPTZ,   -- 首日 − 48h；報價時已過提醒點則 NULL（隨報價通知提醒）
    reminded_at                TIMESTAMPTZ,
    escalate_at                TIMESTAMPTZ,
    escalated_at               TIMESTAMPTZ,
    payee_account_snapshot     TEXT,          -- AES-256-GCM 密文（四欄 JSON），CHARGE + OFFLINE_TRANSFER 必填
    payee_account_key_version  SMALLINT,
    adjustment_basis           VARCHAR(500),  -- ADMIN_ADJUSTMENT 依據
    verified_at                TIMESTAMPTZ,
    verified_by                UUID REFERENCES users(id),
    voided_at                  TIMESTAMPTZ,
    voided_by                  UUID REFERENCES users(id),
    void_reason_code           VARCHAR(24),
    void_reason                VARCHAR(200),
    created_by                 UUID REFERENCES users(id),
    version                    INT NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_payments_amount    CHECK (amount BETWEEN 1 AND 999999),
    CONSTRAINT ck_payments_status    CHECK (status IN ('UNPAID', 'SUBMITTED', 'VERIFIED', 'VOID')),
    CONSTRAINT ck_payments_direction CHECK (
        (direction = 'CHARGE' AND purpose IN ('INITIAL', 'SUPPLEMENT'))
     OR (direction = 'REFUND' AND purpose IN ('MODIFICATION_REFUND', 'ADMIN_ADJUSTMENT'))),
    CONSTRAINT ck_payments_method    CHECK (
        method IN ('OFFLINE_TRANSFER', 'ONLINE', 'ADMIN_ADJUSTMENT')
        AND (purpose = 'ADMIN_ADJUSTMENT') = (method = 'ADMIN_ADJUSTMENT')),
    CONSTRAINT ck_payments_adjustment CHECK (
        method <> 'ADMIN_ADJUSTMENT' OR (status = 'VERIFIED' AND adjustment_basis IS NOT NULL)),
    CONSTRAINT ck_payments_due       CHECK ((purpose IN ('INITIAL', 'SUPPLEMENT')) = (due_at IS NOT NULL)),
    CONSTRAINT ck_payments_escalate  CHECK ((purpose = 'INITIAL') = (escalate_at IS NOT NULL)),
    CONSTRAINT ck_payments_remind    CHECK (remind_at IS NULL OR purpose = 'INITIAL'),
    CONSTRAINT ck_payments_modreq    CHECK (
        (purpose IN ('SUPPLEMENT', 'MODIFICATION_REFUND')) = (modification_request_id IS NOT NULL)),
    CONSTRAINT ck_payments_source    CHECK (source_payment_id IS NULL OR purpose = 'ADMIN_ADJUSTMENT'),
    CONSTRAINT ck_payments_snapshot  CHECK (
        (direction = 'CHARGE' AND method = 'OFFLINE_TRANSFER') = (payee_account_snapshot IS NOT NULL)),
    CONSTRAINT ck_payments_snapshot_key CHECK (
        (payee_account_snapshot IS NULL) = (payee_account_key_version IS NULL)),
    CONSTRAINT ck_payments_verified  CHECK ((status = 'VERIFIED') = (verified_at IS NOT NULL)),
    CONSTRAINT ck_payments_voided    CHECK ((status = 'VOID') = (voided_at IS NOT NULL))
);
-- 同訂單同時最多一筆未結 payment：首次付款只在 PENDING_PAYMENT、補款 / 退款只在變更期間，
-- 而變更同時只有一筆且只能在 CONFIRMED 後發起；也涵蓋「每筆變更同時一筆」
CREATE UNIQUE INDEX ux_payments_open_per_order ON payments (order_id) WHERE status IN ('UNPAID', 'SUBMITTED');
-- 每張訂單一生最多一筆首次付款（訂單取消不重開）
CREATE UNIQUE INDEX ux_payments_initial ON payments (order_id) WHERE purpose = 'INITIAL';
-- 每筆變更一生最多一筆差額 payment（系統退款是新的變更列，不衝突）
CREATE UNIQUE INDEX ux_payments_per_modreq ON payments (modification_request_id)
    WHERE modification_request_id IS NOT NULL;
-- 調解時手動登記的 ADMIN_ADJUSTMENT 每單一筆（baseline O15）
CREATE UNIQUE INDEX ux_payments_adjustment_manual ON payments (order_id)
    WHERE purpose = 'ADMIN_ADJUSTMENT' AND source_payment_id IS NULL;
-- 每筆被作廢的已匯出退款最多補登一筆（PRD-024 BR-14）
CREATE UNIQUE INDEX ux_payments_adjustment_source ON payments (source_payment_id) WHERE source_payment_id IS NOT NULL;
-- C3 / C6 候選（due_at 只存在於 INITIAL / SUPPLEMENT）
CREATE INDEX ix_payments_due ON payments (due_at) WHERE status = 'UNPAID';
-- C2 候選
CREATE INDEX ix_payments_remind ON payments (remind_at) WHERE status = 'UNPAID' AND reminded_at IS NULL;
-- C4 候選
CREATE INDEX ix_payments_escalate ON payments (escalate_at) WHERE status = 'SUBMITTED' AND escalated_at IS NULL;
CREATE INDEX ix_payments_order ON payments (order_id, created_at);

-- ============================================================
-- 9. payment_submissions：每次上傳一列，駁回不清空
-- ============================================================
CREATE TABLE payment_submissions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id           UUID NOT NULL REFERENCES payments(id),
    submitted_by         UUID NOT NULL REFERENCES users(id),
    last_five            CHAR(5) NOT NULL,
    disclaimer_agreed_at TIMESTAMPTZ NOT NULL,
    proof_object_key     VARCHAR(512) NOT NULL,
    proof_sha256         CHAR(64) NOT NULL,
    proof_content_type   VARCHAR(16) NOT NULL,
    proof_size_bytes     INT NOT NULL,
    status               VARCHAR(16) NOT NULL,
    reject_reason        VARCHAR(200),
    reviewed_by          UUID REFERENCES users(id),
    reviewed_at          TIMESTAMPTZ,
    submitted_at         TIMESTAMPTZ NOT NULL,   -- O14「目前這筆 submission 上傳時間」起算點（CQ-64）
    version              INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_submissions_object_key UNIQUE (proof_object_key),
    CONSTRAINT ck_submissions_last_five    CHECK (last_five ~ '^[0-9]{5}$'),
    CONSTRAINT ck_submissions_content_type CHECK (proof_content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_submissions_size         CHECK (proof_size_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT ck_submissions_status       CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_submissions_reject       CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL)),
    CONSTRAINT ck_submissions_reviewed     CHECK ((status = 'SUBMITTED') = (reviewed_at IS NULL))
);
-- 同一 payment 同時最多一筆待核對（baseline §3.3）
CREATE UNIQUE INDEX ux_submissions_open ON payment_submissions (payment_id) WHERE status = 'SUBMITTED';
CREATE INDEX ix_submissions_payment ON payment_submissions (payment_id, submitted_at);

-- ============================================================
-- 10. order_logs：以 operator_role + operator_user_id 取代 operator_id
-- ============================================================
ALTER TABLE order_logs DROP COLUMN operator_id;
ALTER TABLE order_logs ADD COLUMN operator_role    VARCHAR(8) NOT NULL,
                       ADD COLUMN operator_user_id UUID;
ALTER TABLE order_logs ADD CONSTRAINT ck_order_logs_role
    CHECK (operator_role IN ('OWNER','SITTER','ADMIN','SYSTEM'));
CREATE INDEX ix_order_logs_order ON order_logs (order_id, created_at);
-- PRD-010 轉介前置條件查詢（ORDER_REJECTED）
CREATE INDEX ix_order_logs_action ON order_logs (order_id, action_type);

-- ============================================================
-- 11. admin_tasks（baseline §3.6、CQ-55）
-- ============================================================
CREATE TABLE admin_tasks (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type           VARCHAR(40) NOT NULL,
    order_id       UUID NOT NULL REFERENCES orders(id),
    payment_id     UUID REFERENCES payments(id),
    status         VARCHAR(16) NOT NULL,
    opened_by      UUID REFERENCES users(id),   -- SYSTEM 建立為 NULL
    opened_by_role VARCHAR(8) NOT NULL,
    resolution     VARCHAR(24),
    resolved_by    UUID REFERENCES users(id),
    resolved_at    TIMESTAMPTZ,
    version        INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_tasks_type   CHECK (type IN ('INITIAL_PAYMENT_UNVERIFIED', 'MODIFICATION_PAYMENT_DISPUTE', 'SERVICE_DISPUTE')),
    CONSTRAINT ck_admin_tasks_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_admin_tasks_role   CHECK (opened_by_role IN ('OWNER', 'SITTER', 'SYSTEM')),
    CONSTRAINT ck_admin_tasks_resolution CHECK (
        resolution IS NULL OR resolution IN ('SITTER_VERIFIED', 'SITTER_REJECTED', 'ADMIN_VOIDED', 'ADMIN_MEDIATED')),
    CONSTRAINT ck_admin_tasks_resolved CHECK ((status = 'RESOLVED') = (resolution IS NOT NULL AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_admin_tasks_payment  CHECK (type = 'SERVICE_DISPUTE' OR payment_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_admin_tasks_open ON admin_tasks (type, order_id) WHERE status = 'OPEN';
CREATE INDEX ix_admin_tasks_open_list ON admin_tasks (created_at) WHERE status = 'OPEN';
CREATE INDEX ix_admin_tasks_order ON admin_tasks (order_id);  -- ADMIN 唯讀範圍判斷（曾有任何待辦即可讀，不限 OPEN）

-- ============================================================
-- 12. idempotency_records（§3.6.2；擁有者 SD-GLOBAL-SPEC §2.4，DDL 隨本遷移建立）
-- ============================================================
CREATE TABLE idempotency_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    scope           VARCHAR(160) NOT NULL,   -- HTTP method + 路由樣板
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    claim_token     UUID NOT NULL,           -- 每次認領 / 接手換新值；交易 B / C 的寫入都比對（§3.6.2）
    locked_until    TIMESTAMPTZ,
    response_status SMALLINT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_idempotency_scope UNIQUE (user_id, scope, idempotency_key),
    CONSTRAINT ck_idempotency_status    CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_idempotency_lock      CHECK ((status = 'PROCESSING') = (locked_until IS NOT NULL)),
    CONSTRAINT ck_idempotency_response  CHECK ((status = 'COMPLETED') = (response_status IS NOT NULL))
);
CREATE INDEX ix_idempotency_expires ON idempotency_records (expires_at);

-- ============================================================
-- 13–16. 見 SD-FULFILL §5.1（以下照原文）
-- ============================================================
-- ============================================================
-- 13. visits（SD-FULFILL；SD-ORDER 原第 13 段已刪除）
-- ============================================================
DROP INDEX IF EXISTS idx_visits_order;                       -- 被 ix_visits_order_status 前導欄涵蓋
ALTER TABLE visits DROP COLUMN scheduled_at,                 -- 改用 service_date（idx_visits_status_scheduled 隨之刪除）
                   DROP COLUMN is_deleted,                   -- 行程不做軟刪除
                   DROP COLUMN created_by,                   -- 行程操作者記在 order_logs（與 SD-ORDER 一致）
                   DROP COLUMN updated_by;
ALTER TABLE visits RENAME COLUMN finished_at TO completed_at;
ALTER TABLE visits RENAME COLUMN snapshot_plan_title TO booking_plan_title;  -- O1 帶入的方案名稱，報價前顯示用（PRD-008 CQ-15）
ALTER TABLE visits ALTER COLUMN plan_id SET NOT NULL,
                   ALTER COLUMN booking_plan_title SET NOT NULL;
ALTER TABLE visits
    ADD COLUMN order_item_id     UUID NOT NULL REFERENCES order_items(id),
    ADD COLUMN owner_id          UUID NOT NULL REFERENCES users(id),   -- 取自 LockedOrder，訂單當事人不變；供飼主今日行程查詢
    ADD COLUMN sitter_id         UUID NOT NULL REFERENCES users(id),   -- 同上；供保母今日 / 待處理查詢
    ADD COLUMN service_date      DATE NOT NULL,                        -- Asia/Taipei 日期
    ADD COLUMN sequence_in_day   SMALLINT NOT NULL,                    -- 同日序號（第幾次）
    ADD COLUMN slots_in_day      SMALLINT NOT NULL,                    -- 該群組每日次數（預設名稱是否加「第 n 次」）
    ADD COLUMN custom_name       VARCHAR(20),                          -- 保母自訂名稱；NULL = 使用預設名稱
    ADD COLUMN started_at        TIMESTAMPTZ,
    ADD COLUMN completion_source VARCHAR(8),                           -- SITTER / SYSTEM
    ADD COLUMN cancelled_at      TIMESTAMPTZ,
    ADD COLUMN closed_at         TIMESTAMPTZ;
ALTER TABLE visits
    ADD CONSTRAINT ck_visits_status CHECK (status IN ('PENDING','IN_PROGRESS','DONE','CLOSED_BY_SYSTEM','CANCELLED')),
    ADD CONSTRAINT ck_visits_started   CHECK ((status IN ('IN_PROGRESS','DONE')) = (started_at IS NOT NULL)),
    ADD CONSTRAINT ck_visits_done      CHECK ((status = 'DONE') = (completed_at IS NOT NULL)
                                           AND (status = 'DONE') = (completion_source IS NOT NULL)),
    ADD CONSTRAINT ck_visits_source    CHECK (completion_source IS NULL OR completion_source IN ('SITTER','SYSTEM')),
    ADD CONSTRAINT ck_visits_closed    CHECK ((status = 'CLOSED_BY_SYSTEM') = (closed_at IS NOT NULL)),
    ADD CONSTRAINT ck_visits_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    ADD CONSTRAINT ck_visits_slots     CHECK (slots_in_day BETWEEN 1 AND 3 AND sequence_in_day BETWEEN 1 AND slots_in_day),
    ADD CONSTRAINT ck_visits_name      CHECK (custom_name IS NULL OR char_length(custom_name) BETWEEN 1 AND 20),
    ADD CONSTRAINT ux_visits_slot      UNIQUE (order_item_id, service_date, sequence_in_day);
CREATE INDEX ix_visits_order_status   ON visits (order_id, status);                                 -- listVisits、O11 / O15 判斷
CREATE INDEX ix_visits_zombie         ON visits (service_date) WHERE status = 'PENDING';            -- C7 候選
CREATE INDEX ix_visits_in_progress    ON visits (started_at) WHERE status = 'IN_PROGRESS';          -- FC1 候選
CREATE INDEX ix_visits_sitter_date    ON visits (sitter_id, service_date);                          -- F6 今日
CREATE INDEX ix_visits_sitter_open    ON visits (sitter_id, service_date) WHERE status IN ('PENDING','IN_PROGRESS');  -- F6 待處理
CREATE INDEX ix_visits_owner_date     ON visits (owner_id, service_date);                           -- F7

-- ============================================================
-- 14. visit_checklist_items：單日 SOP 檢核項（PRD-028）
-- ============================================================
CREATE TABLE visit_checklist_items (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id       UUID NOT NULL REFERENCES visits(id),
    title          VARCHAR(50) NOT NULL,
    sort_order     SMALLINT NOT NULL,
    is_completed   BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at     TIMESTAMPTZ,
    created_source VARCHAR(8) NOT NULL,                   -- SYSTEM（方案帶入）/ SITTER
    created_by     UUID REFERENCES users(id),             -- SYSTEM 時 NULL；由 Service 寫入，不用 @CreatedBy
    updated_by     UUID REFERENCES users(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_checklist_title   CHECK (char_length(title) BETWEEN 1 AND 50),
    CONSTRAINT ck_checklist_checked CHECK (is_completed = (checked_at IS NOT NULL)),
    CONSTRAINT ck_checklist_source  CHECK (created_source IN ('SYSTEM','SITTER')
                                           AND (created_source = 'SYSTEM') = (created_by IS NULL)),
    CONSTRAINT ck_checklist_sort    CHECK (sort_order >= 0),
    CONSTRAINT ux_checklist_sort    UNIQUE (visit_id, sort_order) DEFERRABLE INITIALLY DEFERRED  -- 排序整批改寫時交易結束才檢查
);

-- ============================================================
-- 15. 照護日誌：visit_service_reports（PRD-022）
-- ============================================================
DROP INDEX IF EXISTS idx_visit_service_reports_visit;
DROP INDEX IF EXISTS idx_service_report_media_report;
ALTER TABLE visit_service_reports DROP COLUMN is_deleted;
ALTER TABLE visit_service_reports
    ADD CONSTRAINT ck_care_log_status    CHECK (status IN ('DRAFT','SUBMITTED')),
    ADD CONSTRAINT ck_care_log_submitted CHECK ((status = 'SUBMITTED') = (submitted_at IS NOT NULL)),
    ADD CONSTRAINT ck_care_log_content   CHECK (content IS NULL OR char_length(content) <= 1000);
-- visit_id UNIQUE（V20260523_03，約束名 visit_service_reports_visit_id_key）保留：一行程一份日誌的最後防線
-- created_by / updated_by（V20260523_03）保留，由 Service 寫入保母 id（PRD-022 BR-24），不經 auditing

-- ============================================================
-- 16. care_log_media_uploads：上傳槽位（§3.3）；service_report_media 改寫
-- ============================================================
CREATE TABLE care_log_media_uploads (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id                  UUID NOT NULL REFERENCES visits(id),
    order_id                  UUID NOT NULL REFERENCES orders(id),
    sitter_id                 UUID NOT NULL REFERENCES users(id),
    media_type                VARCHAR(8)  NOT NULL,
    content_type              VARCHAR(32) NOT NULL,
    declared_size             INT NOT NULL,
    declared_sha256           CHAR(64) NOT NULL,
    declared_duration_seconds SMALLINT,
    caption                   VARCHAR(100),
    object_key                VARCHAR(255) NOT NULL,
    status                    VARCHAR(12) NOT NULL,
    received_at               TIMESTAMPTZ NOT NULL,    -- F16 Controller 進入時的 receivedAt（Service 寫入，非 DB 預設）
    expires_at                TIMESTAMPTZ NOT NULL,    -- received_at + upload-slot-ttl（30m）
    completed_at              TIMESTAMPTZ,
    failure_status            SMALLINT,
    failure_code              VARCHAR(48),
    failure_payload           JSONB,
    object_deleted_at         TIMESTAMPTZ,             -- 只由 FC2 最終清除或 markPurged 寫入
    delete_attempts           SMALLINT NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_uploads_object_key UNIQUE (object_key),
    CONSTRAINT ck_uploads_type      CHECK (media_type IN ('IMAGE','VIDEO')),
    CONSTRAINT ck_uploads_content   CHECK (content_type IN ('image/jpeg','image/png','image/webp','video/mp4','video/quicktime')),
    CONSTRAINT ck_uploads_duration  CHECK ((media_type = 'VIDEO') = (declared_duration_seconds IS NOT NULL)),
    CONSTRAINT ck_uploads_status    CHECK (status IN ('PENDING','COMPLETED','FAILED','DISCARDED','EXPIRED')),
    CONSTRAINT ck_uploads_failed    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT ck_uploads_completed CHECK ((status IN ('COMPLETED','DISCARDED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_uploads_window    CHECK (expires_at > received_at),
    CONSTRAINT ck_uploads_sha       CHECK (declared_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_uploads_size      CHECK (declared_size > 0),
    CONSTRAINT ck_uploads_caption   CHECK (caption IS NULL OR char_length(caption) <= 100)
);
CREATE INDEX ix_uploads_visit   ON care_log_media_uploads (visit_id);
CREATE INDEX ix_uploads_cleanup ON care_log_media_uploads (status, expires_at)
    WHERE object_deleted_at IS NULL AND status <> 'COMPLETED';                                      -- FC2

ALTER TABLE service_report_media DROP COLUMN version, DROP COLUMN is_deleted;   -- 媒體建立後不可修改（說明文字不可改，PRD-022 BR-15）；刪除為實刪
ALTER TABLE service_report_media RENAME COLUMN media_url TO object_key;          -- 私有物件鍵，不存公開網址
ALTER TABLE service_report_media
    ADD COLUMN upload_id         UUID NOT NULL REFERENCES care_log_media_uploads(id),
    ADD COLUMN object_generation BIGINT NOT NULL,       -- F18 檢驗時的 GCS generation；之後刪除帶前置條件
    ADD COLUMN size_bytes        INT NOT NULL,
    ADD COLUMN duration_seconds  SMALLINT,
    ADD COLUMN uploaded_by       UUID NOT NULL REFERENCES users(id),
    ADD COLUMN uploaded_at       TIMESTAMPTZ NOT NULL;
ALTER TABLE service_report_media
    ADD CONSTRAINT ux_report_media_upload    UNIQUE (upload_id),
    ADD CONSTRAINT ux_report_media_object    UNIQUE (object_key),
    ADD CONSTRAINT ck_report_media_type      CHECK (media_type IN ('IMAGE','VIDEO')),
    ADD CONSTRAINT ck_report_media_duration  CHECK ((media_type = 'VIDEO') = (duration_seconds IS NOT NULL)),
    ADD CONSTRAINT ck_report_media_size      CHECK (size_bytes > 0),
    ADD CONSTRAINT ck_report_media_caption   CHECK (caption IS NULL OR char_length(caption) <= 100);
CREATE INDEX ix_report_media_report ON service_report_media (report_id, media_type);   -- 額度計數
-- idx_report_media_purge（V20260703_01，WHERE is_purged = FALSE）保留給 PRD-013 清除排程
