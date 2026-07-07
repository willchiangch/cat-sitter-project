-- V20260707_01: 補齊測試保母 (sitter@test.com) 的 profiles 種子資料
-- is_open 欄位自 V20260606_01 起預設為 false，profiles 又是 app 端 lazy-init 建立，
-- 若尚未有人手動開店，E2E 預約精靈會因 !profile.isOpen 卡在 disabled 的下一步按鈕；
-- is_visible 若為 false 也會觸發 public profile 的 gated 邏輯，一併修正。
-- 這裡直接把測試保母設為已驗證、營業中、可見，符合其他 E2E 測資對「正常保母」的預期狀態。
INSERT INTO profiles (user_id, type, kyc_status, is_open, is_visible)
VALUES ('3d498178-14c0-4376-b81e-7fb02e615dda', 'SITTER', 'VERIFIED', true, true)
ON CONFLICT (user_id, type) DO UPDATE SET kyc_status = 'VERIFIED', is_open = true, is_visible = true;