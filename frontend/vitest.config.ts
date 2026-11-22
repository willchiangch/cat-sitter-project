import { defineConfig } from 'vitest/config';

// 單元測試（SD-FRONTEND-SPEC §1.1 Vitest）。與 vite.config.ts 分開，不載入 PWA plugin。
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    restoreMocks: true,
    unstubGlobals: true
  }
});
