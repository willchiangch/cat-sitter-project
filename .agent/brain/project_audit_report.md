# Project Audit Report - 2026-06-16

## 📊 Summary
- **Overall Status**: COMPLIANT 🟢
- **Critical Issues**: 0
- **Warnings**: 0

---

## 🔍 Detailed Findings

### 1. [Security] IDOR 模糊化防禦與時序攻擊防範
* **端點**：`POST /api/notifications/{id}/read`
* **狀態**：**STATUS: PASS** ✅
* **Details**：為了防範攻擊者對通知 ID 進行暴力枚舉 (Enumeration) 與時序分析攻擊，此端點禁止使用 `403 Forbidden`。無論是越權讀取（非該通知擁有者）或是該通知根本不存在，後端統一拋出 `404 Not Found` (錯誤碼 `MSG_DATA_F11`)，將資料存在性進行模糊化防禦。
* **Remediation**：已於 `NotificationService.markAsRead()` 實作此權限判定，且在 `NotificationServiceTest.should_Throw404_When_IDOR_Detected_Or_NotFound` 中通過越權與隨機 UUID 查詢驗證。

### 2. [Concurrency] 90 天物理清理排程與交易鎖定優化
* **機制**：`POST /api/internal/cron/notifications/cleanup`
* **狀態**：**STATUS: PASS** ✅
* **Details**：
  - 為避免大事務鎖定過久導致 dead tuples 無法被 vacuum，物理清理大於 90 天通知時，必須切分成多個 LIMIT 1000 批次獨立 commit 釋放行級鎖。
  - 為防止同類 (Self-invocation) 呼叫導致 Spring AOP Proxy 繞過使 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 失效之經典 AOP 坑，實作中已徹底分離出 `NotificationCleanupService` (執行大 loop 批次呼叫) 與 `NotificationBatchDeleter` (標註 REQUIRES_NEW 的獨立實體 Bean) 進行跨 Bean 呼叫，確保每個 batch 皆能獨立 Commit。
* **Remediation**：此架構設計與事務流轉已在 `NotificationServiceTest.should_PhysicallyDeleteOldNotificationsInBatches` 中被驗證成功。

### 3. [Specifications] 錯誤碼一致性與 JPA updatable 測試障礙解決
* **狀態**：**STATUS: PASS** ✅
* **Details**：
  - 統一修正偏好設定更新時的無效類別或鎖定類別錯誤碼，將原先與專案其他模組不一致的 `"INVALID_PARAMETER"` 修正為標準的 `"MSG_DATA_INVALID_INPUT"`。
  - 因 `Notification.java` 中的 `createdAt` 設定了 `updatable = false`，測試中無法透過 JPA 更新該時間來模擬 90 天前歷史資料。實作測試中已改用 `jdbcTemplate` 透過原生 SQL 物理修正 `created_at` 欄位以完成分批清理測試。
* **Remediation**：修正已完成，相關整合與控制層測試全部通過。

### 4. [Frontend] Polling 頻率與 Cloud Run 縮容優化
* **狀態**：**STATUS: PASS** ✅
* **Details**：
  - 為避免 Server-Sent Events (SSE) 或 WebSocket 導致的 Cloud Run 實體被長連接綁定而無法自動縮容至 0（從而引發高額非預期帳單），本設計禁用長連接。
  - 改用 React Query 主動拉取 Polling 方案。將未讀數計數 hooks 配置為 `staleTime: 30000`（30秒快取）及 `refetchInterval: 60000`，且配置 `refetchIntervalInBackground: false`，使得網頁縮到背景或隱藏時自動關閉 Polling，兼顧即時性與計費優化。
* **Remediation**：已於 `useNotifications.ts` 實作完成，且前端 Vite 順利編譯通過。

---

## 🚀 Action Items
1. **[Completed]** 修正偏好設定 API 錯誤代碼一致性。
2. **[Completed]** 解決 Spring AOP Proxy 自我呼叫失效問題，打通 REQUIRES_NEW 獨立 batch commit。
3. **[Completed]** 補全 DB check constraint 及 InternalCronController 針對清理端點的 Secret Header 測試。
4. **[Completed]** 實作 AppHeader 小鈴鐺、分頁通知中心、偏好鎖定開關及保母 KYC 置頂橫幅之 UI 元件串接。
