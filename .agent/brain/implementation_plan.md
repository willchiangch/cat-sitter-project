# 毛孩與服務方案數據結構擴充計畫 (V26)

本計畫旨在擴充毛孩資料的維度，包含結紮、疫苗、驅蟲狀態，並同步前後端的動物種類清單與性別驗證邏輯。

## 擬定的變更

### 1. 後端數據模型與枚舉 (Backend)

#### [MODIFY] [PetSpecies.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/enums/PetSpecies.java)
- 更新種類清單：`DOG` (犬), `CAT` (貓), `HAMSTER` (鼠), `RABBIT` (兔), `BIRD` (鳥), `OTHER` (其他)。

#### [NEW] [PetHealthStatus.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/enums/PetHealthStatus.java)
- 定義健康狀態枚舉：`YES` (有), `NO` (沒有), `NOT_REQUIRED` (不需要)。

#### [MODIFY] [Pet.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/Pet.java)
- 將 `gender` 標註為 `@NotNull`。
- 新增 `neuteredStatus` (結紮狀態)。
- 新增 `vaccinationStatus` (疫苗狀態)。
- 新增 `dewormingStatus` (驅蟲狀態)。
- 移除（或標記廢棄）舊的 `isNeutered` 布林欄位。

#### [MODIFY] [CreatePetRequest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/client/CreatePetRequest.java) 與 [PetResponse.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/client/PetResponse.java)
- 補齊上述新欄位的 DTO 映射。

### 2. 資料庫遷移 (Database)

#### [NEW] [V26__expand_pet_details.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V26__expand_pet_details.sql)
- 新增 `neutered_status`, `vaccination_status`, `deworming_status` 欄位（VARCHAR）。
- 將現有 `is_neutered` 資料搬移至 `neutered_status`。
- 設定 `gender` 為 `NOT NULL`（預設為 `UNKNOWN`）。

### 3. API 規格與文件 (Documentation)

#### [MODIFY] [openapi.yaml](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/openapi/openapi.yaml)
- 更新 `Pet` 相關 Schema，加入新屬性。

### 4. 前端介面優化 (Frontend)

#### [MODIFY] [PetFormModal.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/client/PetFormModal.jsx)
- **性別**：設為必填，預設為「請選擇」。
- **動物種類**：更新選單內容（加入「鼠」）。
- **新增必填欄位**：
    - 結紮：有/沒有/不需要。
    - 定期打疫苗：有/沒有/不需要。
    - 定期點驅蟲藥：有/沒有/不需要。

#### [MODIFY] [ServicePackages.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Sitter/ServicePackages.jsx)
- 同步 `SPECIES_OPTIONS` 清單，確保保母建立方案時的種類與毛孩資料一致。

## 驗證計畫

### 自動化測試
- 執行 `mvn test` 確保後端 DTO 映射與驗證正確。
- 檢查 Flyway 遷移紀錄。

### 手動驗證 (Browser)
1. 進入新增毛孩介面，確認性別、結紮、疫苗、驅蟲皆為必填且有正確選項。
2. 進入保母服務方案介面，確認種類清單包含「鼠」。
3. 提交表單，確認資料庫與 API 回傳值皆正確對齊新結構。

## ✅ E2E 驗收結果 (2026-04-11)

**Playwright 40/40 全綠**，修復了以下基礎設施問題：

| 檔案 | 問題 | 修復 |
|------|------|------|
| `SmokeDataSeeder.java` | Fluffy/Oliver 仍用 `IS_NEUTERED` 欄位（V26 已刪除） | 改為 `NEUTERED_STATUS = 'YES'` 等三欄 |
| `ClientPetControllerTest.java` | 5 個 JUnit 測試用舊建構子（含 `boolean isNeutered`） | 全部更新至 V26 `PetHealthStatus` 格式 |
| `v25-soft-delete-and-pets.spec.js` | mock 物件含 `isNeutered: true` | 改為 `neuteredStatus/vaccinationStatus/dewormingStatus` |

新增 `v26-pet-health-status.spec.js`，覆蓋以下 5 個 V26 功能：
1. 結紮/疫苗/驅蟲三選一按鈕存在（有/沒有/不需要）
2. 三組健康狀態標籤皆可見
3. 性別含「不詳 (Unknown)」選項
4. Pets 列表 calculateAge 年齡顯示
5. ServicePackages 含 RABBIT 物種選項
