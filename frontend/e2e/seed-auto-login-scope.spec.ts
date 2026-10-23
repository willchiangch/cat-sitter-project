import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';

/**
 * 迴歸 (Backlog 第 2 批 C1 / 驗收 X2)：Demo 種子帳號自動登入只能在明確的 Demo 模式下發生。
 * 過去 RoleContext 只排除 /login、/register、/forgot-password、/reset-password，匿名訪客開保母公開頁、
 * 或直接開受保護頁面（通知信連結、/booking/{id}）都會在背景以 sitter@test.com 登入。
 * 規則見 src/contexts/seedAutoLogin.ts。
 */
const sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda';

function countSeedLogins(page: Page) {
  const counter = { count: 0 };
  page.on('request', (req) => {
    if (req.method() === 'POST' && new URL(req.url()).pathname === '/api/auth/login') {
      counter.count++;
    }
  });
  return counter;
}

async function mockPublicProfile(page: Page) {
  const authHeaders: (string | undefined)[] = [];
  await page.route(`**/api/sitter/profile/${sitterId}`, async (route) => {
    authHeaders.push(route.request().headers()['authorization']);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        gated: false,
        sitterId,
        displayName: '公開頁測試保母',
        avatarUrl: '',
        bio: '匿名視角測試',
        tags: [],
        serviceAreas: [],
        isOpen: true
      })
    });
  });
  await page.route(`**/api/sitters/${sitterId}/plans`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] })
    });
  });
  return authHeaders;
}

test.describe('Demo 種子帳號自動登入範圍 (C1 / X2 迴歸)', () => {
  test('匿名訪客開保母公開頁：不送出任何登入請求，公開頁 API 不帶 token', async ({ page }) => {
    const logins = countSeedLogins(page);
    const authHeaders = await mockPublicProfile(page);

    await step('匿名開 /sitter/{id}/profile', page, async () => {
      await page.goto(`/sitter/${sitterId}/profile`);
      await expect(page.getByTestId('public-profile-display-name')).toHaveText('公開頁測試保母');
      // RoleProvider 的自動登入 effect 在掛載當下就會發出請求，畫面渲染完成時若有登入一定已經送出
      expect(logins.count).toBe(0);
      expect(authHeaders.length).toBeGreaterThan(0);
      expect(authHeaders.every((h) => h === undefined)).toBe(true);
      expect(await page.evaluate(() => localStorage.getItem('accessToken'))).toBeNull();
    });

    await step('點立即預約導向註冊頁並帶 redirect，仍未登入', page, async () => {
      await page.getByTestId('public-profile-book-now-btn').click();
      await expect(page).toHaveURL(
        new RegExp(`/register\\?redirect=${encodeURIComponent(`/booking/${sitterId}`)}$`)
      );
      await expect(page.getByTestId('register-email-input')).toBeVisible();
      expect(logins.count).toBe(0);
    });
  });

  test('匿名訪客開登入/註冊/忘記密碼頁：不送出任何登入請求', async ({ page }) => {
    const logins = countSeedLogins(page);

    for (const path of ['/login', '/register', '/forgot-password']) {
      await step(`匿名開 ${path}`, page, async () => {
        await page.goto(path);
        await expect(page.getByRole('heading').first()).toBeVisible();
        expect(logins.count).toBe(0);
      });
    }
  });

  test('匿名訪客直接開受保護頁面：不自動登入，導去登入頁並帶 redirect 回原頁', async ({ page }) => {
    const logins = countSeedLogins(page);

    for (const path of [
      `/booking/${sitterId}`,
      '/owner/orders/a1023000-0000-0000-0000-000000000000'
    ]) {
      await step(`匿名開 ${path}`, page, async () => {
        await page.goto(path);
        await expect(page).toHaveURL(new RegExp(`/login\\?redirect=${encodeURIComponent(path)}$`));
        await expect(page.getByTestId('login-email-input')).toBeVisible();
        expect(logins.count).toBe(0);
        expect(await page.evaluate(() => localStorage.getItem('accessToken'))).toBeNull();
      });
    }
  });

  test('Demo 入口 /demo 仍會以種子帳號自動登入；進入 Demo 模式後深連結沿用，公開頁仍不登入', async ({
    page
  }) => {
    const logins = countSeedLogins(page);
    await page.route('**/api/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ accessToken: 'mock-seed-token', refreshToken: 'mock-seed-refresh' })
      });
    });
    const authHeaders = await mockPublicProfile(page);

    await step('開 /demo：送出種子登入，authMode=seed', page, async () => {
      await page.goto('/demo');
      await expect.poll(() => logins.count).toBeGreaterThan(0);
      await expect.poll(() => page.evaluate(() => localStorage.getItem('authMode'))).toBe('seed');
    });

    await step('Demo 模式中直接開受保護頁面：沿用種子 session（重新登入）', page, async () => {
      const before = logins.count;
      await page.goto('/sitter/ledger');
      await expect.poll(() => logins.count).toBeGreaterThan(before);
      await expect(page).toHaveURL(/\/sitter\/ledger$/);
    });

    await step('Demo 模式中開保母公開頁：仍不送登入請求', page, async () => {
      const before = logins.count;
      await page.goto(`/sitter/${sitterId}/profile`);
      await expect(page.getByTestId('public-profile-display-name')).toHaveText('公開頁測試保母');
      expect(logins.count).toBe(before);
      expect(authHeaders.length).toBeGreaterThan(0);
    });
  });

  test('種子自動登入具冪等性：StrictMode 下只送一次；共用種子帳號撞 409 時重送，不會被導去 /login (D4 迴歸)', async ({
    page
  }) => {
    const logins = countSeedLogins(page);
    let served = 0;
    // 第一次回 409（模擬別的分頁/平行 worker 同時登入同一個種子帳號撞樂觀鎖），之後才成功
    await page.route('**/api/auth/login', async (route) => {
      served++;
      if (served === 1) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            message: '內容已被更新，請重新整理後再試',
            error: 'MSG_DATA_CONCURRENCY_CONFLICT'
          })
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ accessToken: 'mock-seed-token', refreshToken: 'mock-seed-refresh' })
      });
    });

    // 登入後頁面打的 API 一律 mock：假 token 打到真後端會 401 → refresh 失敗 → axios 清掉 token
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
    await page.route('**/api/sitter/kyc/status', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { kycStatus: 'VERIFIED', rejectReason: '', submittedAt: '' } })
      })
    );

    await step('開 /demo：第一次 409、重送成功，停在 Demo 首頁', page, async () => {
      await page.goto('/demo');
      await expect(page.getByText('當前角色:')).toBeVisible();
      await expect(page).toHaveURL(/\/demo$/);
      expect(await page.evaluate(() => localStorage.getItem('accessToken'))).toBe(
        'mock-seed-token'
      );
      // dev StrictMode 的 effect 雙呼叫已去重：只有「409 那一次 + 重送一次」，不是兩組並發
      expect(logins.count).toBe(2);
    });
  });
});
