# 毛孩與服務方案數據結構擴充 (V26) 成果回顧

本次更新全面優化了毛孩的基本資訊與健康追蹤，並同步了保母服務方案的配置。

## 變更亮點

### 1. 毛孩資料全面擴充
- **必填驗證**：性別、結紮、打疫苗、打驅蟲藥現在皆為必填項。
- **健康追蹤**：新增「有/沒有/不需要」三個狀態，精確記錄毛孩的照護狀況。
- **性別預設值**：前端預設為「請選擇」，強制使用者進行輸入。

### 2. 動物種類一致性
- **更新清單**：統一為「犬、貓、鼠、兔、鳥、其他」。
- **同步保母端**：保母在建立服務方案時，適用種類清單已同步更新（加入鼠、移除爬蟲）。

### 3. 架構與文檔同步
- **資料庫遷移**：透過 `V26__expand_pet_details.sql` 將舊有的 `is_neutered` (布林值) 平滑遷移至新的 `neutered_status`。
- **API 規格**：`openapi.yaml` 已同步更新新屬性與 `HealthStatus` Schema。

## 變更檔案清單

### 後端實作 (Java)
- [Pet.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/Pet.java): 新增狀態屬性與驗證。
- [PetSpecies.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/enums/PetSpecies.java): 更新枚舉清單。
- [PetHealthStatus.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/enums/PetHealthStatus.java): **[新檔案]** 定义健康狀態枚舉。
- [TargetPetType.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/enums/TargetPetType.java): 同步種類清單。
- [CreatePetRequest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/client/CreatePetRequest.java) & [PetResponse.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/client/PetResponse.java): 擴充資料傳輸格式。
- [ClientPetService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/service/ClientPetService.java): 更新實體與 DTO 的映射邏輯。

### 資料庫與文檔
- [V26__expand_pet_details.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V26__expand_pet_details.sql): 資料遷移。
- [openapi.yaml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi/openapi.yaml): 更新 API 規格。
- [README.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/README.md): 更新版本至 V26。
- [schema.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/doc/schema.md): 更新資料庫規格。

### 前端實作 (React)
- [PetFormModal.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/client/PetFormModal.jsx): 優化增修 UI 與驗證。
- [ServicePackages.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Sitter/ServicePackages.jsx): 同步保母方案的種類清單。

## 驗證結果
- **後端編譯**：`BUILD SUCCESS` (Java 21)。
- **資料遷移**：Flyway 邏輯已就緒，下次啟動時會自動處理。

> [!IMPORTANT]
> 由於資料結構有變動，若現有前端代碼有緩存舊的資料結構，建議重新整理頁面以確保欄位正確映射。

---

## 🧪 V26 E2E 測試補齊 (Phase 14 — 2026-04-11)

**Playwright 40/40 全綠**（前次 35 + V26 新增 5）

### 修復要點

| 檔案 | 問題 | 修復 |
|------|------|------|
| `SmokeDataSeeder.java` | Fluffy/Oliver INSERT 仍含 `IS_NEUTERED` 欄位，V26 已刪除 | 改為三欄 `NEUTERED/VACCINATION/DEWORMING_STATUS = 'YES'/'NO'` |
| `ClientPetControllerTest.java` | 5 個 JUnit 測試用舊建構子 `(boolean isNeutered)` | 全部改為 `PetHealthStatus` enum；Pet 實體也補上三個 NOT NULL 欄位 |
| `v25-soft-delete-and-pets.spec.js` | mock MOCK_PET 含 `isNeutered: true` | 改為 `neuteredStatus/vaccinationStatus/dewormingStatus` |

### 新增測試（`v26-pet-health-status.spec.js`）
1. PetFormModal 結紮三選一按鈕（有/沒有/不需要）
2. 三組健康狀態標籤全可見（結紮/疫苗/驅蟲）
3. 性別含「不詳 (Unknown)」選項
4. Pets 列表 `calculateAge` 年齡顯示（birthDate 有值時）
5. ServicePackages 編輯介面含 RABBIT 物種
