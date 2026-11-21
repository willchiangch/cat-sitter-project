---
name: e2e-write
description: 依 Approved 的 TS 撰寫或補齊 Playwright spec，接著可進 /e2e-cycle 執行與驗收。用法：/e2e-write <TS-NNN | TS-JOURNEY-NN ...>
argument-hint: "<TS-NNN | TS-JOURNEY-NN ...>"
disable-model-invocation: true
---

# /e2e-write

參數：`$ARGUMENTS`

1. **關卡**：TS 必須 Approved；對應 SD 存在（取 testid 與 API）。
2. 每份 TS 派一個 e2e-author，多份平行（各自寫不同 spec 檔，不可同時改同一支 spec 或 `support/*`；需要改 `support/*` 的先單獨派一個做完再派其他）。
3. e2e-author 回報需要後端配合（provision 參數、cleanup）→ 派 backend-dev 補；回報缺 testid 已自行加上 → 列給使用者。
4. 回報：spec 清單、TS 步驟對應率、lint / tsc 結果、需要使用者知道的 TS 與程式不符之處。
5. 下一步：
   - mock spec：e2e-author 已在本機跑過，直接 commit。
   - journey spec：`/e2e-cycle local <spec>`（會在正式站建 `@e2e-journey.test` 帳號，跑完自動清）。

執行、根因分析、修 bug、驗收一律交給既有的 `/e2e-cycle`（runner → triage → fixer → acceptance），這裡只負責「寫」。
