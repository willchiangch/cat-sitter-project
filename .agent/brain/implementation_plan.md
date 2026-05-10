# SA 審查問題修復與補強計畫 (Implementation Plan)

本計畫基於您對 `sa_review_report.md` 的回饋，以及系統原先盤點出的邏輯矛盾進行修復規劃。

## User Review Required

> [!IMPORTANT]
> **關於「飼主我的最愛保母」功能**
> 為了不讓 `PRD-005` (預約流程) 變得過於龐大，我建議將「我的最愛 (Favorite Sitters)」獨立寫成一份 **`PRD-019-favorite-sitters.md`**。飼主可以在這個清單中快速查看收藏的保母，並且當保母「暫時關閉公開頁面」時，飼主會在此清單看到保母呈現「灰色/隱藏中」的狀態。
> **請問您同意將其獨立為 PRD-019 嗎？**

> [!NOTE]
> **關於「每月接單上限」的定義 (PRD-012)**
> 為了後續 SD 階段資料庫的統計方便，我預計將「每月」定義為 **「自然月 (每月 1 日至月底)」**，而計量單位為 **「訂單數 (Order)」** 而非行程數 (Visit)。
> **請問這符合您的商業期待嗎？**

---

## Proposed Changes

以下是我們預計執行的修改步驟，分為四個主要階段：

### Phase 1: 處理新增的核心業務邏輯 (User Feedback)

#### [MODIFY] [PRD-018-public-profile-management.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-018-public-profile-management.md)
- **公開狀態切換 (Public / Hidden)**：在保母檔案中新增開關。
- **呈現邏輯**：當狀態為 Hidden 或「訪問者被列入黑名單」時，應跳轉至統一的「保母目前不公開/不開放預約」提示頁，而非 404。只有當帳號已完全註銷時，才顯示 404。

#### [NEW] `PRD-019-favorite-sitters.md`
- **加入時機**：支援「交易完成後快速加入」以及「透過帳號 ID 主動搜尋加入」。
- **隱私規則**：加入「我的最愛」為飼主單向行為，**不需通知保母**。
- **狀態連動**：名單內會顯示保母最新狀態 (開放中 / 隱藏中)。

#### [MODIFY] [PRD-000-auth-login.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-000-auth-login.md)
- **帳號註銷 (Soft Delete)**：對使用者而言是永久刪除，但系統後端採**軟刪除 (Soft Delete)** 機制，保留歷史訂單與財務紀錄以供查核。
- **AC 重新編號**：修復重複的 AC 編號。

---

### Phase 2: 訂單生命週期補強

#### [MODIFY] [PRD-016-order-modification-cancellation.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-016-order-modification-cancellation.md)
- **保母發起變更**：加入「保母主動發起取消/縮減天數」的流程。系統將寄發通知給飼主，飼主同意後生效並計算退款。

#### [MODIFY] [PRD-009-order-completion.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-009-order-completion.md)
- **雙軌爭議處理**：
  - **線上支付訂單**：點擊「回報問題」將暫停 D+3 撥款倒數，資金保留在平台代管，直到爭議解除。
  - **線下支付 (現金/轉帳) 訂單**：因款項已由保母預收，點擊「回報問題」為**「申訴存證」**與**「保母評價信用扣分 (未來擴充)」**性質。系統會發送通知給保母與管理員，並在訂單狀態標記為 `DISPUTED` 以供人工介入調解。
  - **結案限制**：無論何種支付，處於 `DISPUTED` 狀態皆不可自動或手動執行 `COMPLETED`。

---

### Phase 3: 金流與權限邏輯對齊

#### [MODIFY] [PRD-015-payment-integration.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-015-payment-integration.md)
- **銀行帳戶綁定**：加入保母填寫銀行代碼、分行與帳號的具體操作與驗證流程。
- **線上/線下路徑釐清**：解決與 PRD-007 的衝突。定義線上刷卡成功後，訂單直接進入 `CONFIRMED` 狀態，跳過需人工核對的 `PAID` 狀態。

#### [MODIFY] [PRD-008-service-execution.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-008-service-execution.md)
- **多媒體限制對齊**：將「免費版/基礎版」的描述嚴格拆開，對齊 PRD-012 Feature Matrix 的規定 (Free: 3張, Basic: 10張)。

#### [MODIFY] [PRD-012-platform-subscription.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/PRD-012-platform-subscription.md)
- **明確接單上限定義**：將「每月接單上限」寫明為「自然月」與「訂單 (Order)」。

---

### Phase 4: 總表重構

#### [MODIFY] [00-PRD-PLAN.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/docs/sa/fr/00-PRD-PLAN.md)
- 重新整理 1~19 的編號順序。
- 修正檔名錯誤 (如 `PRD-015` 等)。
- 確保總表內的功能描述與最新修改的 PRD 內容 (如：退款僅限縮減天數、無自動續約) 完全一致。

---

## Verification Plan

### Manual Verification
- 您審閱修改後的各份 PRD 文件，確認業務邏輯已完美閉環，且所有您的回饋皆已落實。
- 確認無誤後，我們即可正式將這 20 份文件 (含 00-PRD-PLAN) 作為 SA 的最終交付物，並轉入 SD (System Design) 階段。
