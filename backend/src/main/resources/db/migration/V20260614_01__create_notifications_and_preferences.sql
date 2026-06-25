-- V20260614_01: Create Notifications and Notification Preferences Tables
-- Standards: SD-GLOBAL-SPEC, SD-ERD, SD-014

-- 1. Create notifications table
CREATE TABLE notifications (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL,
    title         varchar(150) NOT NULL,
    content       text NOT NULL,
    category      varchar(50) NOT NULL,
    is_read       boolean NOT NULL DEFAULT false,
    read_at       timestamp with time zone,
    link_url      varchar(255),
    role_target   varchar(20) NOT NULL DEFAULT 'ALL',
    
    -- Base entity audit fields
    version       integer NOT NULL DEFAULT 0,
    created_at    timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    updated_at    timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    created_by    uuid,
    updated_by    uuid,
    is_deleted    boolean NOT NULL DEFAULT false,
    
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_notification_category CHECK (category IN ('ORDER_AFFAIR','ACCOUNT_AUTH','SUBSCRIPTION_MAINTENANCE','SERVICE_RECORD'))
);

-- Indexing for optimized querying and range scans (unread count, recent 10 logs)
CREATE INDEX idx_notifications_user_role_read_created ON notifications(user_id, role_target, is_read, created_at);
CREATE INDEX idx_notifications_created_at ON notifications(created_at);

-- 2. Create notification_preferences table
CREATE TABLE notification_preferences (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL,
    category      varchar(50) NOT NULL,
    enable_in_app boolean NOT NULL DEFAULT true,
    enable_email  boolean NOT NULL DEFAULT true,
    
    -- Base entity audit fields
    version       integer NOT NULL DEFAULT 0,
    created_at    timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    updated_at    timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    created_by    uuid,
    updated_by    uuid,
    is_deleted    boolean NOT NULL DEFAULT false,
    
    PRIMARY KEY (id),
    CONSTRAINT fk_preference_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_user_category UNIQUE (user_id, category),
    CONSTRAINT chk_pref_category CHECK (category IN ('ORDER_AFFAIR','ACCOUNT_AUTH','SUBSCRIPTION_MAINTENANCE','SERVICE_RECORD')),
    CONSTRAINT chk_pref_account_auth_locked CHECK (category != 'ACCOUNT_AUTH' OR (enable_in_app AND enable_email))
);

CREATE INDEX idx_notification_prefs_user ON notification_preferences(user_id);
