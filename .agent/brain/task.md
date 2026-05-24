# WhiskerWatch 開發任務與 Roadmap

## 1. 已完成階段：SD-009 & SD-016 前端實作與聯調
- [x] 後端與基礎 API 準備（Flyway admin 種子、orderApi.ts）
- [x] 路由與角色同步機制（RoleContext.tsx 多角色與 JWT 自動同步、App.tsx 路由與 Demo 入口）
- [x] 實作訂單結案與爭議 (SD-009) 前端頁面（OwnerOrders、OwnerOrderDetail、OrderDisputeModal、AdminResolvePanel、SitterOrders 狀態擴充）
- [x] 實作雙向變更與退款流轉 (SD-016) 前端精靈（OrderModificationWizard、SitterModificationQuote、OwnerModificationConfirm）
- [x] 整合測試驗證（Playwright E2E 6 個測試情境 100% 綠燈通過）
- [x] 同步大腦文件與專案 README.md

---

## 2. 下階段任務：SD-003 保母端「服務方案管理頁」前端實作
- [ ] 基礎 API Client 準備
  - [ ] 建立 `sitterPlanApi.ts` 封裝服務方案的 CRUD、動態排序（sort）等介面
- [ ] 實作服務方案管理 UI（Stitch 憲法規範）
  - [ ] 新增 `ServicePlanManager.tsx` (保母方案管理首頁)
  - [ ] 新增 `ServicePlanForm.tsx` (包含基本資料、自訂任務 SOP 編輯、適用寵物類型勾選、白名單限制)
  - [ ] 實作 SaaS 限制與日期區間控制的前置 UI 提示與防禦
  - [ ] 實作拖曳或鍵盤排序的批次排序 UI
- [ ] 路由與 Demo 整合
  - [ ] 修改 `App.tsx` 的 `ViewState` 引入 `service-plan-manager` 頁面狀態
  - [ ] 於 Demo 首頁增加快速進入「保母服務方案管理」按鈕
- [ ] 整合測試驗證
  - [ ] 撰寫 `frontend/e2e/service-plan-management.spec.ts`
  - [ ] 驗證方案新增、編輯、排序與 SaaS Gating 白名單是否能在 UI 上正常卡控與展示

---

## 3. 後玩規劃：SD-002 毛孩管理一條龍開發
- [ ] 撰寫 `SD-002` 毛孩管理與資料關聯系統設計文件
- [ ] 後端毛孩資料庫設計與 Flyway 遷移 (Pet Schema, breed, age, weight, vaccine status)
- [ ] 後端 Pet Entity、Repository、Service、Controller (CRUD) 實作
- [ ] 前端實作：飼主毛孩管理面板 (`PetManager.tsx`, `PetCard.tsx`, `PetFormModal.tsx`)
- [ ] 預約精靈 (PublicBookingPage) 串接實體毛孩選擇與快照機制
- [ ] 撰寫 Playwright E2E 驗證毛孩管理與預約鏈路

---

## 4. 後續規劃：SD-001 帳號 Profile 實作
- [ ] 撰寫 `SD-001` 個人基本資料與密碼修改設計文件
- [ ] 後端 Profile 修改 API 與密碼重設 (BCrypt 雜湊加密) 實作
- [ ] 前端實作：帳號基本資料管理頁 (`ProfileEditor.tsx`)
