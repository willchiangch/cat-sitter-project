-- PRD-010: 信任圈與轉介通知需要新增 REFERRAL 通知類別
ALTER TABLE notifications DROP CONSTRAINT chk_notification_category;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_category
    CHECK (category IN ('ORDER_AFFAIR','ACCOUNT_AUTH','SUBSCRIPTION_MAINTENANCE','SERVICE_RECORD','REFERRAL'));

ALTER TABLE notification_preferences DROP CONSTRAINT chk_pref_category;
ALTER TABLE notification_preferences ADD CONSTRAINT chk_pref_category
    CHECK (category IN ('ORDER_AFFAIR','ACCOUNT_AUTH','SUBSCRIPTION_MAINTENANCE','SERVICE_RECORD','REFERRAL'));
