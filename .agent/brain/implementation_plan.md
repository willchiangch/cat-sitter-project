# Automation of Cloud Run Proxy for E2E Testing

This plan adds scripts to automate starting the Google Cloud Run proxy when running E2E tests locally. This ensures that tests run against the live Cloud Run instance while maintaining security (authentication).

## Proposed Changes

### Configuration

#### [MODIFY] [.env](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/.env)
Add a new variable for the GCP Project ID to be used by the scripts.
- `GCP_PROJECT_ID=your-project-id-here`

### Dependencies

#### [MODIFY] package.json (frontend)
Install development dependencies:
- `npm install -D concurrently wait-on`

### Scripts

#### [MODIFY] [package.json](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/package.json)
Add the following scripts:
- `cloud-run:proxy`: Starts the gcloud proxy manually.
- `test:e2e:cloud`: Automates "Start Proxy -> Wait for Proxy -> Run Playwright -> Stop Proxy".

### Playwright Configuration

#### [NEW] [playwright.cloud.config.ts](file:///Users/will_chiang/Widget_home/cat-sitter-project/frontend/playwright.cloud.config.ts)
Create a specialized configuration for Cloud Run testing that:
- Inherits from the base configuration.
- Disables the local backend `webServer` to avoid port conflicts and ensure we test against the proxy.

## Verification Plan

### Automated Tests
- Run `npm run test:e2e:cloud` and verify it correctly starts the proxy and tries to run tests.
- (Manual check) Verify `npm run cloud-run:proxy` starts the proxy as expected.

### Manual Verification
- User to fill in the `GCP_PROJECT_ID` in `.env` and run the script.
