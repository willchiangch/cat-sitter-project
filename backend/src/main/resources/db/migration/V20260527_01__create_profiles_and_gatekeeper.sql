-- V20260527_01: 建立 profiles 表與 gatekeeper_rules 門禁表，並擴充 refresh_tokens 與 log_user_action
-- Standards: SD-GLOBAL-SPEC, SD-ERD, SD-001

-- 1. 建立 Profile 檔案側表
CREATE TABLE profiles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id),
    type        VARCHAR(50) NOT NULL, -- SITTER, CLIENT
    trust_score INT NOT NULL DEFAULT 100, -- 內部信用分數，高度隱私
    kyc_status  VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 防止 lazy initialization 競爭衝突
CREATE UNIQUE INDEX uidx_profiles_user_type ON profiles(user_id, type);

-- 2. 建立預約門禁規則表
CREATE TABLE gatekeeper_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_id       UUID NOT NULL REFERENCES users(id),
    rule_type       VARCHAR(50) NOT NULL, -- BLACK, WHITE, NO_QUESTIONNAIRE
    scope_type      VARCHAR(50) NOT NULL, -- GLOBAL, PLAN
    plan_id         UUID REFERENCES service_plans(id),
    target_user_id  UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_plan_scope CHECK (scope_type != 'PLAN' OR plan_id IS NOT NULL)
);

-- 門禁互斥局部唯一索引
-- 2.1 全域黑白單互斥
CREATE UNIQUE INDEX uidx_gatekeeper_global_excl ON gatekeeper_rules(sitter_id, target_user_id) 
WHERE scope_type = 'GLOBAL' AND rule_type IN ('BLACK', 'WHITE');

-- 2.2 方案黑白單互斥
CREATE UNIQUE INDEX uidx_gatekeeper_plan_excl ON gatekeeper_rules(sitter_id, plan_id, target_user_id) 
WHERE scope_type = 'PLAN' AND rule_type IN ('BLACK', 'WHITE');

-- 2.3 重複規則防呆 (GLOBAL & PLAN)
CREATE UNIQUE INDEX uidx_gatekeeper_global_duplicate ON gatekeeper_rules(sitter_id, rule_type, target_user_id)
WHERE scope_type = 'GLOBAL';

CREATE UNIQUE INDEX uidx_gatekeeper_plan_duplicate ON gatekeeper_rules(sitter_id, rule_type, plan_id, target_user_id)
WHERE scope_type = 'PLAN';

-- 2.4 優化檢索索引
CREATE INDEX idx_gatekeeper_sitter ON gatekeeper_rules(sitter_id);

-- 3. 建立操作日誌表 (補足多租戶審計規範)
CREATE TABLE log_user_action (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    func_code    VARCHAR(100) NOT NULL,
    action_type  VARCHAR(50) NOT NULL,
    action_result VARCHAR(50) NOT NULL DEFAULT 'SUCCESS',
    operator_id  UUID REFERENCES users(id),
    target_id    UUID,
    target_table VARCHAR(100),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. 擴充 refresh_tokens 表，新增 active_role 欄位儲存切換角色狀態
ALTER TABLE refresh_tokens ADD COLUMN active_role VARCHAR(50);
