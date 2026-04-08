import { defineConfig, devices } from '@playwright/test';
import baseConfig from './playwright.config';

/**
 * Playwright configuration for Cloud Run testing.
 * This config inherits from the base config but removes the local backend webServer,
 * as it expects the gcloud run services proxy to be running.
 */
export default defineConfig({
  ...baseConfig,
  // Use a longer timeout for Cloud Run cold starts
  timeout: 120 * 1000,
  expect: {
    /**
     * Maximum time expect() should wait for the condition to be met.
     * For Cloud Run, we use a longer timeout to handle cold starts.
     */
    timeout: 20000,
  },
  use: {
    ...baseConfig.use,
    // The baseURL for the frontend remains the same (localhost:5173)
    // but the backend will be accessed via the proxy on localhost:8081
  },
  // Keep the frontend webServer, but remove the backend one
  webServer: baseConfig.webServer ? [baseConfig.webServer[0]] : undefined,
});
