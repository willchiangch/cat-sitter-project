# 預約精靈 UI 排版與月曆組件優化實作計畫 (極簡並排版)

為了解決在特定解析度或容器限制下，「每日次數」與「選擇日期」控制列超出邊界的問題，我們引進極簡化與垂直緊湊的現代 UI 設計。

## User Review Required

> [!IMPORTANT]
> 1. **選擇日期純 Icon 化**：將「選擇日期」按鈕移除文字，改為純主色圓形 Icon 按鈕。點擊即可彈出月曆，維持 `data-testid` 不變。
> 2. **每日次數兩行化**：將「每日次數」容器改為垂直雙行佈局。第一行為精緻小字標籤「每日次數」，第二行為小巧的減/加按鈕與數值，減少水平佔用空間。
> 3. **極窄並排**：兩者水平並排，整體寬度將大幅縮減至約 150px 左右，徹底消除在小寬度容器下的溢出問題。

## Proposed Changes

---

### 前端 UI 優化

#### [MODIFY] [PublicBookingPage.tsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/client/PublicBookingPage.tsx)

1. **選擇日期按鈕極簡化**：
   - 將 `<button data-testid="client-booking-open-calendar-pIdx-sIdx">` 修改為 `width: '44px'`, `height: '44px'`, `borderRadius: '50%'` 的純圓形 Icon 按鈕，僅保留 `CalendarDays` Icon。
   - 加上 `title="選擇日期"` 強化 Accessibility。

2. **每日次數兩行式佈局**：
   - 外部包裹容器改用 `flexDirection: 'column'` 進行垂直排列，並加上細微內陰影與圓角（`borderRadius: '16px'`）。
   - 「每日次數」文字尺寸縮小為 `0.75rem`，以小標籤形式呈現。
   - 微調加減按鈕尺寸為 `24px * 24px`，Icon 尺寸改為 `10`，以保持視覺比例協調與極致精細感。

## Verification Plan

### Automated Tests
- 在前端目錄下執行 `npx playwright test`，驗證所有 E2E 測試案例是否仍穩定通過。

### Manual Verification
- 檢查變更後的 UI 視覺，確認選擇日期圓形 Icon 按鈕與兩行式每日次數卡片在同一水平線上並排，無任何溢出或不對齊。
