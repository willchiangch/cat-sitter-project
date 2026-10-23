import path from 'path';
import { fileURLToPath } from 'url';
import type { Page } from '@playwright/test';

/**
 * mock E2E 共用：以 Demo 模式 (authMode=seed) 進站，種子登入回假 token，
 * 並攔截 AppHeader 每頁都會打的通知 API，避免假 token 打到真後端 401 → refresh 失敗 → token 被清掉。
 * 頁面本身的 API 由各 spec 自己 mock。
 */
export async function mockDemoSession(page: Page, role: 'sitter' | 'client' | 'admin') {
  await page.addInitScript((r) => {
    if (!localStorage.getItem('authMode')) {
      localStorage.setItem('authMode', 'seed');
      localStorage.setItem('userRole', r);
    }
  }, role);
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ accessToken: `mock-${role}-token`, refreshToken: 'mock-refresh' })
    })
  );
  await page.route('**/api/notifications/unread-count**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"data":0}' })
  );
  await page.route(/\/api\/notifications(\?.*)?$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }
      })
    })
  );
}

const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), '../journeys/fixtures');

/** 把一個假的簽名網址接到本機 fixture 圖片（GCS 簽名網址的替身） */
export async function serveFixtureImage(page: Page, url: string, fixture: string) {
  await page.route(url, (route) =>
    route.fulfill({ status: 200, contentType: 'image/jpeg', path: path.join(FIXTURES, fixture) })
  );
}
