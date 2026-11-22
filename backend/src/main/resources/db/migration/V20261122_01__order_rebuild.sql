SET lock_timeout = '10s';   -- Flyway 在同一交易內執行，效果等同 LOCAL（SD-ORDER §5.1）

-- ============================================================
-- SD-ORDER §5.1.1 / SD-FULFILL §5.1：V20261122_01__order_rebuild.sql
--
-- 重建 A 階段（全域共用機制）只放第 12 段 idempotency_records。
-- 第 0–11 段（清空交易資料、orders / modification_requests / order_items / order_item_pets /
-- order_answers / order_plan_snapshots / order_snapshots / payments / payment_submissions /
-- order_logs / admin_tasks）由 B 階段插入在第 12 段「之前」；
-- 第 13–16 段（visits、visit_service_reports、service_report_media、visit_checklist_items、
-- care_log_media_uploads，SD-FULFILL §5.1）由 B 階段接在第 12 段「之後」。
-- 本檔在同一次發布前仍會被修改：已在本機跑過 A 階段版本的 DB 需 flyway repair 或重建。
-- ============================================================

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
