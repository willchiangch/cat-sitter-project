---
description: 如何同步開發進度至專案庫以確保跨工具/環境的連續性
---

為了確保在重置工具、切換電腦或使用不同 AI 助理時，開發進度不會丟失，請遵循以下步驟同步「大腦」資料：

1. **工作階段結束時**：
   - 將當前所有 `brain` 相關文件（`task.md`, `project_evaluation.md`, `walkthrough.md`, `implementation_plan.md`）從工具私有目錄同步至專案根目錄的 `.agent/brain/` 文件夾。
   - `cp <tool_private_brain_path>/*.md .agent/brain/`

2. **新工作階段開始時**：
   - 優先讀取 `.agent/brain/` 下的文件以獲取最新的專案上下文、已完成項目與待辦事項。
   - 根據讀取到的 `task.md` 恢復工作狀態。

3. **提交程式碼時 (Git Commit)**：
   - 確保 `.agent/brain/` 下的文件也一併提交。
   - **檢查祕密安全性**：確保 `.env` 不被追蹤，但務必同步更新 `backend/.env.example` 與 `frontend/.env.example` 以供其他環境參考。
