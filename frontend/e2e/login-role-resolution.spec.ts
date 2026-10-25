import { test, expect, type Page } from '@playwright/test';
import { step, apiStep } from './support/evidence';
import { assertNoUnmockedApi, mockSessionApis, unsignedJwt } from './support/mockSession';

/**
 * fix-batch12 J5（驗收截圖新問題 7）：登入後的角色過去只從 JWT 的 role claim 解析、忽略回應的 role 欄位；
 * 解不出 claim 時不寫也不清 localStorage.userRole，而 session 失效時 axiosClient 只清 token、不清 userRole，
 * 下一位登入的使用者就沿用上一位的角色（保母登入 → 被登出 → 飼主登入，畫面仍是保母）。
 * 現在以回應的 role 為準、JWT 為輔，都解不出才清除（contexts/loginRole.ts）。
 */
const readAuth = (page: Page) =>
  page.evaluate(() => ({
    accessToken: localStorage.getItem('accessToken'),
    userRole: localStorage.getItem('userRole')
  }));

const roleLine = (page: Page) => page.locator('p', { hasText: '當前角色:' });

async function loginViaForm(page: Page, email: string) {
  await page.getByTestId('login-email-input').fill(email);
  await page.getByTestId('login-password-input').fill('password123');
  await page.getByTestId('login-submit-btn').click();
}

test.describe('J5 登入角色以回應 role 為準，不沿用上一位使用者', () => {
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('保母登入 → session 失效被登出 → 飼主登入（token 解不出 role claim）：角色為飼主', async ({
    page
  }) => {
    await mockSessionApis(page);
    const loginBodies: unknown[] = [];
    await page.route('**/api/auth/login', (route) => {
      const body = route.request().postDataJSON() as { email: string };
      loginBodies.push(body);
      const isSitter = body.email === 'sitter-j5@test.com';
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          isSitter
            ? {
                accessToken: unsignedJwt({ role: 'SITTER', sub: body.email }),
                refreshToken: 'j5-sitter-refresh',
                role: 'SITTER'
              }
            : {
                // 解不出 claim 的 token（不是 JWT）：舊版只看 JWT，會沿用上一位的 userRole
                accessToken: 'opaque-owner-token',
                refreshToken: 'j5-owner-refresh',
                role: 'OWNER'
              }
        )
      });
    });
    // 帳務頁一律 401、refresh 也 401 → N16 的 session 失效流程（清 token、導 /login）
    await page.route('**/api/orders/sitter/ledger**', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'UNAUTHORIZED', message: '未認證的請求，請先登入' })
      })
    );
    const refreshBodies: unknown[] = [];
    await page.route('**/api/auth/refresh', (route) => {
      refreshBodies.push(route.request().postDataJSON());
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'UNAUTHORIZED', message: 'Refresh token 找不到或無效' })
      });
    });

    await page.goto('/login');
    await step('保母登入：Demo 首頁顯示「貓咪保母」', page, async () => {
      await loginViaForm(page, 'sitter-j5@test.com');
      await expect(page).toHaveURL(/\/demo$/);
      await expect(roleLine(page)).toHaveText('當前角色: 貓咪保母');
      expect((await readAuth(page)).userRole).toBe('sitter');
    });

    await step(
      'session 失效被登出：導向登入頁，token 已清、userRole 仍留著保母',
      page,
      async () => {
        await page.goto('/sitter/ledger');
        await expect(page).toHaveURL(/\/login\?redirect=%2Fsitter%2Fledger$/);
        await expect(page.getByTestId('login-email-input')).toBeVisible();
        await expect.poll(() => readAuth(page)).toEqual({ accessToken: null, userRole: 'sitter' });
      }
    );

    await page.goto('/login');
    await step('飼主登入（token 不是 JWT，回應 role=OWNER）：顯示「愛貓飼主」', page, async () => {
      await loginViaForm(page, 'owner-j5@test.com');
      await expect(page).toHaveURL(/\/demo$/);
      await expect(roleLine(page)).toHaveText('當前角色: 愛貓飼主');
      expect(await readAuth(page)).toEqual({
        accessToken: 'opaque-owner-token',
        userRole: 'client'
      });
    });

    await apiStep('登入 / refresh 請求紀錄與最終 localStorage', async () => {
      expect(loginBodies).toHaveLength(2);
      expect(refreshBodies).toEqual([{ refreshToken: 'j5-sitter-refresh' }]);
      return { loginBodies, refreshBodies, auth: await readAuth(page) };
    });
  });
});
