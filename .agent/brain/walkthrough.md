# Cloud Run Proxy Automation for E2E Testing

We have successfully integrated an automated workflow to run E2E tests against the Cloud Run backend while maintaining proper authentication via `gcloud run services proxy`.

## Changes Made

### Configuration
- **[MODIFY] [.env](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/.env)**: Added `GCP_PROJECT_ID`, `GCP_REGION`, and `GCP_SERVICE_NAME` as configuration variables.

### Dependencies
- Installed `concurrently` and `wait-on` in `frontend` to manage the lifecycle of the proxy server alongside the test runner.

### New Config & Scripts
- **[NEW] [playwright.cloud.config.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/playwright.cloud.config.ts)**: A specialized Playwright configuration that inherits from the base config but excludes the local backend server, allowing tests to run against the cloud proxy.
- **[MODIFY] [package.json](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/package.json)**:
    - `npm run cloud-run:proxy`: For manual proxy connection.
    - `npm run test:e2e:cloud`: The automated "all-in-one" command for Cloud E2E testing.

### Documentation
- **[MODIFY] [README.md](file:///Users/will_chiang/Widget_home/cat-sitter-project/README.md)**: Added a new section "Cloud Run Proxy 驗證 (UAT)" with clear instructions.

## How to use

### 1. Preparation
Edit `frontend/.env` and set your `GCP_PROJECT_ID`:
```env
GCP_PROJECT_ID=your-actual-project-id
```

### 2. Run Automated E2E
```bash
cd frontend
npm run test:e2e:cloud
```
This command will:
1. Start the gcloud proxy in the background.
2. Wait for the proxy to be ready (responding at `http://localhost:8081`).
3. Run Playwright tests hitting the proxy.
4. Automatically shut down the proxy once tests are finished.

> [!TIP]
> Make sure you are logged in via `gcloud auth login` before running these commands.
