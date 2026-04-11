# 任務清單 - 毛孩數據結構擴充 (V26)

## 後端實作 [x]
- [x] 更新 `PetSpecies` 加入 `HAMSTER`
- [x] 建立 `PetHealthStatus` 枚舉
- [x] 更新 `Pet` 實體與相關屬性
- [x] 更新 `CreatePetRequest` 與 `PetResponse` DTO
- [x] 更新 `ClientPetService` 映射邏輯
- [x] 更新 `TargetPetType` 枚舉
- [x] 實作資料庫遷移腳本 `V26__expand_pet_details.sql`

## API 文件 [x]
- [x] 更新 `backend/openapi/openapi.yaml`
- [x] 更新 `doc/schema.md`
- [x] 更新 `README.md`

## 前端實作 [x]
- [x] 更新 `PetFormModal.jsx` (性別必填、新增健康狀態欄位、物種清單同步)
- [x] 更新 `ServicePackages.jsx` (同步動物種類清單)

## 驗證 [x]
- [x] 重新編譯後端並驗證 Flyway 遷移
- [x] 手動測試毛孩與方案建立流程 (前端 UI 與後端 DTO 驗證)
- [x] 同步專案大腦文檔 (.agent/brain)

## E2E 測試補齊與基礎設施修復 (Completed — 2026-04-11)
- [x] 修正 `SmokeDataSeeder.java`：Fluffy/Oliver 兩隻寵物的 INSERT 從舊 `IS_NEUTERED` 欄位改為新的 `NEUTERED_STATUS`/`VACCINATION_STATUS`/`DEWORMING_STATUS` 欄位（V26 遷移後必須）
- [x] 修正 `ClientPetControllerTest.java`：5 個 JUnit 測試用舊建構子 / 缺 NOT NULL 欄位，全部更新至 V26 格式
- [x] 更新 `v25-soft-delete-and-pets.spec.js`：mock PetResponse 由 `isNeutered: true` 改為 `neuteredStatus/vaccinationStatus/dewormingStatus`
- [x] 新增 `v26-pet-health-status.spec.js`（5 個 V26 功能 E2E 測試）
  - [x] PetFormModal 顯示「結紮」三選一按鈕（有/沒有/不需要）
  - [x] PetFormModal 包含三組健康狀態標籤（結紮/疫苗/驅蟲）
  - [x] PetFormModal 性別含「不詳 (Unknown)」選項
  - [x] Pets 列表顯示 `calculateAge` 年齡字串（birthDate 有值時）
  - [x] ServicePackages 服務方案編輯介面含 RABBIT 物種
- [x] **最終測試結果：40/40 通過**（前次 35 + V26 新增 5）
