import { test, expect } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockSessionApis } from './support/mockSession';

test.describe('PRD-000 AC-5 Google 第三方登入流程', () => {
  test.setTimeout(60000);
  // 驗收 N16 起 session 失效（refresh 失敗）會導去 /login：登入成功後回的是假 token，AppHeader 的通知 / KYC API
  // 打到後端 401 → refresh 401 → 被導回 /login?redirect=%2Fdemo（正式 build 上比斷言 /demo 還快）。
  // 改接共用的 header API mock + 漏 mock 偵測（N9）
  test.beforeEach(async ({ page }) => {
    await mockSessionApis(page);
  });
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('新 Email 首次使用 Google 登入時應顯示角色選擇，選擇後完成登入', async ({ page }) => {
    let callCount = 0;
    await page.route('**/api/auth/google', async (route) => {
      callCount += 1;
      if (callCount === 1) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 'NEEDS_ROLE_SELECTION',
            email: 'newgoogleuser@test.com',
            fullName: 'New Google User'
          })
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 'SUCCESS',
            accessToken: 'fake',
            refreshToken: 'fake',
            userId: '1',
            role: 'OWNER'
          })
        });
      }
    });

    await page.goto('/login');
    await page.waitForFunction(
      () => typeof (window as any).__handleGoogleCredential === 'function'
    );
    await page.evaluate(() =>
      (window as any).__handleGoogleCredential({ credential: 'fake-google-id-token' })
    );

    await expect(page.getByTestId('google-role-select-banner')).toBeVisible();
    await page.getByTestId('google-role-select-input').selectOption('OWNER');
    await step('選擇飼主角色後完成登入，停在 Demo 首頁', page, async () => {
      await page.getByTestId('google-role-select-confirm-btn').click();
      await expect(page).toHaveURL(/\/demo$/);
      await expect(page.getByText('當前角色:')).toBeVisible();
      expect(callCount).toBe(2);
    });
  });

  test('已存在帳號的 Email 使用 Google 登入應直接自動綁定並登入', async ({ page }) => {
    await page.route('**/api/auth/google', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'SUCCESS',
          accessToken: 'fake',
          refreshToken: 'fake',
          userId: '1',
          role: 'OWNER'
        })
      });
    });

    await page.goto('/login');
    await page.waitForFunction(
      () => typeof (window as any).__handleGoogleCredential === 'function'
    );
    await step('既有帳號 Google 登入後直接進 Demo 首頁', page, async () => {
      await page.evaluate(() =>
        (window as any).__handleGoogleCredential({ credential: 'fake-google-id-token' })
      );
      await expect(page).toHaveURL(/\/demo$/);
      await expect(page.getByText('當前角色:')).toBeVisible();
    });
  });
});
