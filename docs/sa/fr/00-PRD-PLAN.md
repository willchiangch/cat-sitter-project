# 貓咪保母 PWA - PRD 撰寫規劃 (SA 功能性需求總表)

依據業務需求與 SaaS 四級訂閱架構，將系統拆分為以下 20 份獨立的 PRD。

---

## 🚀 迭代開發藍圖 (Implementation Roadmap)

本專案採三階段迭代開發，確保以最快速度驗證核心業務價值。

### Phase 1：核心閉環 (Minimum Viable Loop)
**目標**：建立第一個完整的服務流程，支援線下交易，完成產品價值交付。
- **開發重點**：PRD-000, 001, 018, 002, 003, 005, 006, 007, 008, 009。
- **核心價值**：保母上架 -> 飼主預約 -> 線下支付 -> 服務執行 -> 結案回報。

### Phase 2：商用擴展 (Commercial Scalability)
**目標**：建立商業獲利模式、法律防禦與異常處理機制。
- **開發重點**：PRD-017 (KYC), 015 (線上支付), 012 (SaaS 訂閱), 016 (訂單變更), 020 (管理後台), 013 (儲存管理)。
- **核心價值**：信任基礎、平台營收、例外自動化、運維工具。

### Phase 3：生態與優化 (Growth & Network Effect)
**目標**：提升使用者留存、建立引薦網絡與極致體驗。
- **開發重點**：PRD-010 (信任圈), 011 (行事曆同步), 014 (通知中心), 019 (我的最愛), 004 (自訂問卷優化)。
- **核心價值**：網路效應、生產力工具、品牌忠誠度。

---

## PRD 文件對照表 (Document Index)


## 核心設定模組 (Core)

0. **`PRD-000-auth-login.md`**: 帳號註冊與登入認證
   - 包含 Email/Google 登入、生物辨識、**帳號註銷 (軟刪除)** 與 Email 驗證碼 (OTP)。
1. **`PRD-001-role-profile.md`**: 角色切換與檔案管理
   - 角色切換 (Client/Sitter)、**預約門禁設定**、**飼主地址填寫 (供保母評估距離)**。
2. **`PRD-017-sitter-kyc.md`**: 保母實名認證與資格審查 (KYC)
   - 身分證與自拍照上傳、管理員審核、接單資格解鎖 (`VERIFIED`)。
3. **`PRD-018-public-profile-management.md`**: 保母公開檔案與標籤管理
   - 自我介紹、大頭貼、自訂標籤、服務區域、**公開/隱藏狀態切換與呈現邏輯**。
4. **`PRD-002-pet-management.md`**: 毛孩資料與注意事項管理
   - 多種寵物種類 (狗貓鳥鼠等) CRUD、生活習性注意事項、協作編輯紀錄。
5. **`PRD-003-service-plans.md`**: 保母服務方案設定
   - 方案 CRUD、時長、適用寵物、生效區間、**特定檔期控制 (專業版以上)**、白名單綁定。
6. **`PRD-004-questionnaire.md`**: 事前問卷設定
   - 問卷題目 CRUD、排序、對應不同服務方案之彈性調用。
7. **`PRD-019-favorite-sitters.md`**: 飼主我的最愛保母功能
   - 收藏機制、保母 ID 搜尋、狀態連動 (開放/隱藏)、隱私保護 (不通知保母)。
7.1 **`PRD-021-care-notes-and-media.md`**: 保母飼主記事本與媒體庫
   - **核心記事 (6 大項分類)**、**拖拽排序與自動序號**、**記事模板 (上限 3)**、**環境說明媒體庫 (上限 20)**。

## 訂單生命週期 (Order Lifecycle)

> 2026-11-21 重建：以下訂單交易相關 PRD 依 `docs/sa/00-DOMAIN-BASELINE.md` 重寫，歸屬 SD-ORDER（收款帳號歸 SD-SUPPLY）。

8. **`PRD-005-public-booking.md`**: 公開預約頁與送出預約（O1）
   - 可預約判斷與門禁、日期群組 / 方案 / 後端試算、月接單上限、緊急單評估期限、自我預約禁止。
8.1 **`PRD-026-owner-withdraw-booking.md`**: 飼主撤回預約（付款前，O3 OWNER / O6）
9. **`PRD-006-order-evaluation.md`**: 保母評估、事前問卷與報價（O2）
   - 問卷子狀態與熟客免填、原價 / PRO+ 手動調價、報價時快照、建立首次收款 payment。
9.1 **`PRD-027-order-decline-expiry-cancel.md`**: 保母婉拒、評估逾期與付款前保母取消（O3 SITTER / O4 / O7）
10. **`PRD-007-offline-payment.md`**: 線下付款憑證上傳與核對
    - payment / submission 共通行為、首次付款期限與提醒、待核對升級、O5 / O8 / O16。
10.1 **`PRD-025-sitter-payout-account.md`**: 保母收款帳號設定（歸 SD-SUPPLY）
11. **`PRD-008-service-execution.md`**: 行程生命週期：開始、結束、系統關閉與行程檢視（歸 SD-FULFILL）
11.1 **`PRD-028-visit-sop-checklist.md`**: 行程單日 SOP 檢核項（歸 SD-FULFILL）
12. **`PRD-009-order-completion.md`**: 結案、服務爭議與保母帳務總覽（歸 SD-FULFILL；O11–O13 API 由 SD-ORDER 提供）
13. **`PRD-016-order-modification-cancellation.md`**: 訂單變更：發起、報價與協商期限
13.2 **`PRD-023-order-modification-settlement.md`**: 訂單變更：飼主回應、補款、退款與結清（含 O10）
13.3 **`PRD-024-order-modification-dispute.md`**: 訂單變更：款項爭議（O14）與協商期間的訂單限制
13.1 **`PRD-022-care-log.md`**: 照護日誌與多媒體（歸 SD-FULFILL；結束行程後 24h 內送出、媒體額度依報價快照）

## 進階系統與平台模組 (Advanced)

14. **`PRD-010-trust-circle-referral.md`**: 信任圈與轉介機制
    - 建立信任圈、**轉介前黑名單自動過濾**、轉介通知與推薦語。
15. **`PRD-011-calendar-sync.md`**: 行事曆自動同步模組
    - Google OAuth、實體映射、雙向同步、**異動自動通知飼主**。
16. **`PRD-012-platform-subscription.md`**: 平台 SaaS 訂閱方案與權限鎖定
    - 四級方案定義、配額計量、**降級強制下架保護**、**取消訂單配額返還**。
17. **`PRD-013-media-retention.md`**: 工作日誌媒體生命週期管理
    - **依訂單成交時方案快照保留檔案**、升級追溯延長、降級保護。
18. **`PRD-014-notification-center.md`**: 通知中心與訊息範本
    - 站內通知、Email 發送、訊息類別管理、使用者免打擾設定。
19. **`PRD-015-payment-integration.md`**: 線上支付與金流整合
    - **TWD 貨幣整數儲存 (INT)**、D+3 撥款、**退款手續費平台吸收**、銀行帳戶綁定。
20. **`PRD-020-admin-dashboard.md`**: 系統管理後台 (Admin Dashboard)
    - 集中營運工作：KYC 審核、**手動方案展期 (取代折扣碼)**、爭議訂單調解與刷退、敏感詞庫維護。
