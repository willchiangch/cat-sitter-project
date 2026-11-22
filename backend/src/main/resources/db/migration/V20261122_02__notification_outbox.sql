-- ============================================================
-- notifications 投遞欄位（outbox，baseline §3.6、SD-ORDER §2.3.3 / §8.4.1 / §10.4）。
-- 擁有者為 SD-NOTIFY（設計文件未撰寫）；B 階段依 SD-ORDER 已定的欄位名與 emailStatus 值域過渡建立，
-- 與 V20261122_01 同批發布。SD-NOTIFY 撰寫時以本檔為現況。
-- ============================================================
ALTER TABLE notifications
    ADD COLUMN type                  VARCHAR(48),
    ADD COLUMN mandatory             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN related_order_id      UUID,          -- 只作連結（ER：outbox link only），不設 FK
    ADD COLUMN email_status          VARCHAR(24) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN email_attempts        SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN email_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN email_last_error      VARCHAR(500);
ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_email_status CHECK (email_status IN
        ('PENDING','SENT','SKIPPED_PREFERENCE','FAILED','NOT_APPLICABLE','SUPPRESSED_TEST')),
    ADD CONSTRAINT ck_notifications_email_attempts CHECK (email_attempts BETWEEN 0 AND 5);
-- C11 候選
CREATE INDEX ix_notifications_email_dispatch ON notifications (email_next_attempt_at) WHERE email_status = 'PENDING';
-- §10.4 inspect 依訂單查詢
CREATE INDEX ix_notifications_related_order ON notifications (related_order_id) WHERE related_order_id IS NOT NULL;
