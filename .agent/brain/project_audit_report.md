# Project Audit Report - 2026-05-23

## 📊 Summary
- **Overall Status**: COMPLIANT 🟢
- **Critical Issues**: 0
- **Warnings**: 0

---

## 🔍 Detailed Findings

### [Security] IDOR 防護 (SaaS Gating & Access Control)
- **CareNoteController & CareMediaController GET 端點**: **STATUS: PASS** ✅
  - **Evidence**: [CareNoteController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/CareNoteController.java#L34) 與 [CareMediaController.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/interfaces/controller/CareMediaController.java#L35)
  - **Details**: 原先 GET 端點只檢查 `sitterId`，導致飼主呼叫自己資料時會被拒絕 (403)。目前已成功更新為 OR 邏輯：`sitterId.equals(userId) || ownerId.equals(userId)`，飼主與保母均可放行，其餘無關使用者返回 403。
  - **Remediation**: 已修正完畢並通過 E2E 整合測試。

- **CareMediaService.deleteMedia**: **STATUS: PASS** ✅
  - **Evidence**: [CareMediaService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/CareMediaService.java#L107)
  - **Details**: 刪除權限不足時，已由原本的 `IllegalArgumentException` 改為拋出 `AccessDeniedException`，以確保 Web 安全層正確攔截並回傳 403。
  - **Remediation**: 已修正完畢並通過測試。

### [Concurrency] 冪等性與 JPA 行為限制 (Idempotency Key)
- **IdempotencyService & IdempotencyKey JPA merge 修正**: **STATUS: PASS** ✅
  - **Evidence**: [IdempotencyService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/application/service/IdempotencyService.java#L22-L25)
  - **Details**: 由於 `IdempotencyKey` 的主鍵是複合鍵 `(idempotencyKey, userId)`，當我們對一個已存在該主鍵的實體呼叫 `save` 時，JPA 會因為該主鍵不為空而判定為 `merge` (update) 行為，從而不會拋出 `DataIntegrityViolationException`。目前已加上 `idempotencyKeyRepository.existsById(id)` 顯式檢測，若存在則手動拋出 `DataIntegrityViolationException("Duplicate idempotency key detected")`，觸發 409 Conflict。
  - **Remediation**: 已修正完畢，且 `CareNoteControllerTest` 與 `CareMediaControllerTest` 中的冪等衝突測試皆順利通過 (409)。

### [Concurrency] JPA Save merge 與手動 assignment 修正
- **CareMedia Entity 手動 ID 與 Persistable 實作**: **STATUS: PASS** ✅
  - **Evidence**: [CareMedia.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/petsitter/domain/model/CareMedia.java#L18)
  - **Details**: 原本 `CareMedia` 的 `id` 被標記為 `@GeneratedValue` 卻在 `CareMediaService.uploadMedia` 被手動指定值，導致 Hibernate 將其判定為 detached 實體，在 `save` 結束後拋出 `StaleObjectStateException`。目前已移除 `@GeneratedValue` 並且讓其正確實作 `Persistable<UUID>` 接口，在 `isNew()` 判定時回傳 `true` 以呼叫 `persist`。
  - **Remediation**: 已修正完畢，所有控制器測試均完美運行。

---

## 🚀 Action Items
1. **[Completed]** 解決所有 P0 安全越權與 IDOR 漏洞。
2. **[Completed]** 修正 API 冪等性的 JPA merge 更新漏洞。
3. **[Completed]** 補全 Service 層與 Controller 整合層測試。
