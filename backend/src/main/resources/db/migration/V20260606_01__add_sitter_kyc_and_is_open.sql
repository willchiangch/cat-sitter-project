-- V20260606_01: 新增保母 KYC 紀錄表與 is_open 欄位，並將 profiles.kyc_status 預設值修正為 UNVERIFIED

-- 1. 於 profiles 表新增 is_open 欄位，預設為關閉 (false)，並新增 version 欄位以支援樂觀鎖
ALTER TABLE profiles ADD COLUMN is_open BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE profiles ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

-- 2. 修正 profiles 既有欄位 kyc_status 的預設值，並將存量 PENDING 恢復為 UNVERIFIED
ALTER TABLE profiles ALTER COLUMN kyc_status SET DEFAULT 'UNVERIFIED';
UPDATE profiles SET kyc_status = 'UNVERIFIED' WHERE kyc_status = 'PENDING';

-- 3. 新建 kyc_records 實體表
CREATE TABLE kyc_records (
    id                      uuid NOT NULL DEFAULT gen_random_uuid(),
    sitter_id               uuid NOT NULL,
    id_card_front_key       varchar(512) NOT NULL,
    selfie_key              varchar(512) NOT NULL,
    status                  varchar(50) NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
    reject_reason           varchar(500),
    reviewed_by             uuid,
    reviewed_at             timestamp with time zone,
    
    -- 審計欄位
    created_at              timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    updated_at              timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    created_by              uuid,
    updated_by              uuid,
    is_deleted              boolean NOT NULL DEFAULT false,
    version                 integer NOT NULL DEFAULT 0,
    
    PRIMARY KEY (id),
    CONSTRAINT fk_kyc_sitter FOREIGN KEY (sitter_id) REFERENCES users(id),
    CONSTRAINT fk_kyc_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
);

-- 4. 建立索引加速後台篩選與防重提交
CREATE INDEX idx_kyc_sitter_id ON kyc_records(sitter_id);
CREATE INDEX idx_kyc_status ON kyc_records(status);

-- 5. 建立局部唯一索引，保證單一保母同時間最多僅能有一筆 PENDING 的 KYC 紀錄，防堵併發重複提交
CREATE UNIQUE INDEX idx_kyc_sitter_pending_unique
    ON kyc_records(sitter_id)
    WHERE status = 'PENDING';
