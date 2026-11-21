---
name: doc-conventions
description: 本專案 PRD / NFR / SD / TS 文件的命名、編號、狀態、AC 格式與追溯規則。撰寫、審查或讀取 docs/sa、docs/sd、docs/test-scenario 下任何文件前必須先讀。
user-invocable: false
---

# 文件規範

## 命名與編號

| 文件 | 檔名 | ID | 範本 / 寫作 skill |
|------|------|----|------|
| 功能需求 | `docs/sa/fr/PRD-NNN-{kebab-slug}.md` | `PRD-NNN` | `docs/sa/fr/_TEMPLATE.md`、`sa-skill` |
| 非功能需求 | `docs/sa/nfr/NN-{TOPIC}.md` | `NFR-NN` | `docs/sa/nfr/_TEMPLATE.md`、`sa-nfr-skill` |
| 模組設計 | `docs/sd/SD-{MODULE}.md`（例 `SD-ORDER.md`） | `SD-{MODULE}` | `docs/sd/_TEMPLATE.md`、`sd-skill` |
| 舊版模組設計 | `docs/sd/SD-NNN-{slug}.md` | `SD-NNN` | 重建期間僅作參考；新模組 SD 核准後狀態改 `Superseded` 並註明取代者 |
| 領域基準 | `docs/sa/00-DOMAIN-BASELINE.md` | `BASELINE` | 角色矩陣、SaaS 額度、訂單狀態機、金流、時間規則的唯一定義 |
| 全域設計 | `docs/sd/SD-GLOBAL-SPEC.md`、`SD-FRONTEND-SPEC.md`、`SD-ERD.md` | — | 取代 LEDA 的 `shared/*`，新的共用規則寫進這三份 |
| 測試情境 | `docs/test-scenario/TS-NNN-{kebab-slug}.md` | `TS-NNN` | `docs/test-scenario/_TEMPLATE.md`、`ts-skill` |
| 跨模組旅程 | `docs/test-scenario/TS-JOURNEY-NN-{slug}.md` | `TS-JOURNEY-NN` | 同上，自動化路徑 `frontend/e2e/journeys/*.spec.ts` |
| 規劃總表 | `docs/sa/fr/00-PRD-PLAN.md`、`docs/sd/00-SD-PLAN.md`、`docs/sa/nfr/00-NFR-OVERVIEW.md` | — | 新增或拆分文件時同步更新 |
| 文件審查紀錄 | `docs/reviews/{文件檔名}.review.md` | — | prd-reviewer / sd-reviewer 輸出 |
| 實作審查報告 | `reports/code-review/{yyyyMMdd-HHmm}-sd-nnn.md` | — | code-reviewer 輸出 |
| E2E 執行紀錄 | `.e2e-runs/{yyyyMMdd-HHmm}/` | — | e2e-runner / triage / fixer / acceptance |

- **PRD ↔ TS 同號 1:1**：`PRD-022` ↔ `TS-022`。跨模組旅程另用 `TS-JOURNEY-NN`。
- **SD 依業務模組撰寫，一份涵蓋多張 PRD**：每張有效 PRD 恰好屬於一份 SD，對應表以 `docs/sd/00-SD-PLAN.md` §0「模組與 PRD 對應」為準；PRD 表頭「歸屬 SD」須與之一致。一張 PRD 的子流程分屬不同模組時，拆 PRD 而不是讓兩份 SD 共用一張 PRD。
- 模組 SD 擁有自己的 table（表頭「擁有的 Table」），其他 SD 只能透過它的 Service / API 存取。
- 新編號 = 現有最大號 + 1；廢棄編號不重用。
- 表頭是 Markdown 表格（`| 狀態 | ... |`），不是 YAML front matter。

## AC 格式與卡片大小

- AC 寫在 PRD「驗收標準（AC）」段：`- [ ] **AC-{子模組}.{序號} (短標題)**：...`，例 `AC-1.7`。沒有子模組的舊卡用 `AC-n`。
- 引用時一律寫全名：`PRD-022 AC-1.7`。
- **單一子模組 AC ≤ 8 條**（sa-skill 8 AC 原則）；超過就切子模組，整張 PRD 子模組 > 3 個時拆新 PRD。
- AC 必須可觀察：寫出 HTTP 狀態 / 錯誤碼（`409 VERSION_CONFLICT`）、狀態值（`DRAFT → SUBMITTED`）、畫面文案或 testid，禁用「正確顯示」「適當」「快速」。

## 狀態

```
Draft → In Review → Needs Clarification ⇄ In Review → Approved → Superseded
```

| 狀態 | 誰可以設定 |
|------|-----------|
| Draft / In Review | 撰寫 agent |
| Needs Clarification | 審查 agent |
| Approved / Superseded | **只有使用者**（或使用者在當次對話明確說「核准 X」後由主流程代寫） |

- 舊文件有非標準值：`**Approved**`、`Approved (…)`、`Approved with Consultant Feedback` 視同 Approved；`COMPLIANT`、`Implemented & Verified` 視同 Approved（實作狀態請改寫到 README「模組狀態」表，下次修訂時順手把狀態欄正規化）。
- 修改已 Approved 文件的**規格內容**（AC、API 契約、資料表、狀態機）→ 狀態退回 `In Review`，文件末「變更紀錄」寫日期與原因。僅補充「本測試發現並修復的真實 bug」、修錯字、同步實作細節不退回。

## 追溯規則

- PRD 每條 AC → TS「二、測試步驟」至少一列（列的說明欄註明 `AC-x.y`）。
- PRD 每條 AC → 所屬模組 SD 的「AC 對應實作」表（必填，範本 §1）。
- AC → 後端測試：新測試 `@DisplayName` 以 `PRD-NNN AC-x.y` 開頭。舊測試不強制改名。
- TS → spec：TS 表頭「自動化路徑」必須指向存在的 `frontend/e2e/**/*.spec.ts`；spec 的 `step()` 標籤以 TS 步驟編號開頭（例 `B-06`），供 e2e-acceptance 對應。
- **改 code 同步改文件**：牽動 AC、API 契約、資料表、狀態機的變更，同一個 commit 更新對應 PRD / SD / TS。刻意偏離原設計時把決策理由寫進 SD。

## 寫作規則

- 繁體中文；Markdown 表格儲存格內不用 HTML 標籤（`<br>`、`<ul>`），分項用分號或多列。
- 需求沒出處（`docs/requirements/*`、使用者在對話中的決定、既有 Approved PRD）不得寫成規格，列入「待釐清事項」並附**建議預設值**。
- 已定案、不要再提出的產品決策（審查時不要列為問題）：
  - 不做接單容量 / 檔期控管（`dailyCapacity` 已移除），只保留單張訂單併發保護。
  - 不處理同一使用者多裝置自我併發，只處理跨角色或會造成資料錯誤的競爭。
  - 訂單保留議價機制 + 原價快速通道，兩者收斂到同一快照邏輯。
