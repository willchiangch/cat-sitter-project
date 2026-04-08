# Universal UUID Alignment for E2E Pass Rate

The system currently has major UUID discrepancies between the Frontend test mocks (`AuthPage.js`), the Backend security filter (`SmokeMockAuthFilter.java`), and the Database seed data (`V22`). This causes 401 Unauthorized errors and "data not found" (like missing Fluffy) during E2E runs.

## Proposed Changes

### 1. Unified ID Specification
To ensure absolute alignment, everything will use these exact IDs:
- **Sophia (SITTER)**: Account `efefefef-...-0001` / Profile `efefefef-...-0001`
- **James (CLIENT)**: Account `efefefef-...-0002` / Profile `efefefef-...-0002`
- **Newbie**: Account `efefefef-...-0003`

### 2. Backend Logic Update
#### [MODIFY] [SmokeMockAuthFilter.java](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/java/com/catsitter/api/security/SmokeMockAuthFilter.java)
- Align `NEWBIE` to `...0003`.
- Add support for `CLIENT` header as an alias for `JAMES`.
- Add support for `SOPHIA` header as an alias for `SITTER`.

### 3. Database Seeding Update
#### [MODIFY] [V22__sync_e2e_mock_data.sql](file:///Users/will_chiang/Widget_home/cat-sitter-project/backend/src/main/resources/db/migration/V22__sync_e2e_mock_data.sql)
- Ensure James Profile uses `...0002`.
- Ensure Sophia Profile uses `...0001`.
- Ensure James's pet "Fluffy" is correctly associated with Profile `...0002`.

### 4. Frontend Test Utility Update
#### [MODIFY] [AuthPage.js](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/tests/pages/AuthPage.js)
- Update `injectSmokeAuth` to use the unified IDs (`...0001`, `...0002`, `...0003`).
- Ensure `profiles` arrays in the mock state match the new unified Profile IDs.
- Ensure `headers['X-Smoke-Auth']` uses values recognized by the backend.

## Verification Plan

### Automated Tests
- Redeploy Backend to Cloud Run.
- Run `npm run test:e2e:cloud`.
- Expected: 30/30 passed.
