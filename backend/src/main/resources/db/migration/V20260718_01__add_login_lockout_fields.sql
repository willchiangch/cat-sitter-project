-- PRD-000 AC-4: 連續 5 次登入失敗需鎖定帳號 10 分鐘
ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;
