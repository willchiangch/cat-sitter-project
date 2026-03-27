# Implementation Plan - Identity & Role Switching

為了支持前端的身分切換功能，需要實作 `GET /api/v1/auth/me` API，回傳該帳戶目前所擁有的所有角色與 Profile。

## Proposed Changes

### [Component] Identity API

#### [MODIFY] [openapi.yaml](file:///backend/openapi/openapi.yaml)
- 更新 `MeResponse` 結構，加入 `profiles` 列表，包含 `profileId`, `role`, `name`, `avatarUrl`。

#### [NEW] [AuthMeResponse.java](file:///backend/src/main/java/com/catsitter/api/dto/auth/AuthMeResponse.java)
- 定義回傳結構：`accountId`, `email`, `profiles` (List of `ProfileSummary`)。

#### [MODIFY] [AuthController.java](file:///backend/src/main/java/com/catsitter/api/controller/v1/AuthController.java)
- 實作 `GET /api/v1/auth/me` 接口。

#### [MODIFY] [AuthService.java](file:///backend/src/main/java/com/catsitter/api/service/AuthService.java)
- 查詢帳戶關聯的所有 `Profile` 並封裝成 `AuthMeResponse`。

## Verification Plan

### Automated Tests
- **AuthControllerTest**: 驗證 `GET /api/v1/auth/me` 能正確回傳包含 CLIENT 與 SITTER (如果有的話) 的 Profile 列表。

### Manual Verification
- 登入後使用 Swagger UI 呼叫 `/api/v1/auth/me`。
