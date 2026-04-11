# 寵物資料擴充與驗證介面優化

本次計畫將解決媒體存取失敗問題，並擴充寵物資料欄位與優化身分驗證顯示。

## 使用者評論需求 (User Review Required)

> [!IMPORTANT]
> - **Port 衝突修正**：Vite 目前代理至 `8081`，但後端運行於 `8080`，這導致了 `ECONNREFUSED`。我將修正為 `8080` 以恢復圖片顯示。
> - **寵物資料結構變更**：我會在後端實體與 DTO 加入 `birthDate`，並在前端計算年齡顯示。
> - **驗證狀態顯示**：在 Profile 頁面加入綠色勾勾與文字狀態，讓使用者一眼看出信箱驗證情形。

## 擬定的變更 (Proposed Changes)

### 1. 基礎配置修復 (Foundation Fix)

#### [MODIFY] [vite.config.js](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/vite.config.js)
- 將 `target` 從 `http://localhost:8081` 修改為 `http://localhost:8080`。

---

### 2. 後端：寵物實體與枚舉擴充 (Backend: Pet Entity & Enums)

#### [MODIFY] [PetSpecies.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/enums/PetSpecies.java)
- 加入 `RABBIT` 選項。

#### [MODIFY] [Pet.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/entity/Pet.java)
- 加入 `LocalDate birthDate` 欄位與 Getter/Setter。

#### [MODIFY] [CreatePetRequest.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/client/CreatePetRequest.java)
- 加入 `LocalDate birthDate`。

#### [MODIFY] [PetResponse.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/dto/client/PetResponse.java)
- 加入 `LocalDate birthDate`。

#### [MODIFY] [ClientPetService.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/service/ClientPetService.java)
- 更新 Mapping 邏輯，處理 `birthDate` 的存取。

---

### 3. 前端：UI 組件優化 (Frontend: UI Components)

#### [MODIFY] [Profile.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/pages/Auth/Profile.jsx)
- 在電子郵件欄位旁加入「已驗證（綠勾）」或「未驗證」標籤。
- 修改寵物列表，加入年齡計算邏輯（例如：3歲 2個月）。

#### [MODIFY] [PetFormModal.jsx](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/src/components/client/PetFormModal.jsx) (假設路徑)
- 加入「出生年月日」選擇器。
- 更新「種類」選擇器，對齊保母建立方案時的分類（貓、狗、兔、鳥、其他）。

## 驗證計畫 (Verification Plan)

### 手動驗證
1. **圖片讀取**：確認修正 Proxy 後大頭照能正常顯示。
2. **驗證勾勾**：完成驗證後回到 Profile 頁面檢查綠勾。
3. **寵物年齡**：新增一隻設定出生日期的寵物，檢查顯示介面是否正確換算為年齡。
