import { test, expect } from '@playwright/test';

test.describe('PRD-000 AC-5 Google 第三方登入流程', () => {
  test.setTimeout(60000);

  test('新 Email 首次使用 Google 登入時應顯示角色選擇，選擇後完成登入', async ({ page }) => {
    let callCount = 0;
    await page.route('**/api/auth/google', async (route) => {
      callCount += 1;
      if (callCount === 1) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ status: 'NEEDS_ROLE_SELECTION', email: 'newgoogleuser@test.com', fullName: 'New Google User' })
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ status: 'SUCCESS', accessToken: 'fake', refreshToken: 'fake', userId: '1', role: 'OWNER' })
        });
      }
    });

    await page.goto('/login');
    await page.waitForFunction(() => typeof (window as any).__handleGoogleCredential === 'function');
    await page.evaluate(() => (window as any).__handleGoogleCredential({ credential: 'fake-google-id-token' }));

    await expect(page.getByTestId('google-role-select-banner')).toBeVisible();
    await page.getByTestId('google-role-select-input').selectOption('OWNER');
    await page.getByTestId('google-role-select-confirm-btn').click();

    await expect(page).toHaveURL(/\/demo/);
    expect(callCount).toBe(2);
  });

  test('已存在帳號的 Email 使用 Google 登入應直接自動綁定並登入', async ({ page }) => {
    await page.route('**/api/auth/google', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', accessToken: 'fake', refreshToken: 'fake', userId: '1', role: 'OWNER' })
      });
    });

    await page.goto('/login');
    await page.waitForFunction(() => typeof (window as any).__handleGoogleCredential === 'function');
    await page.evaluate(() => (window as any).__handleGoogleCredential({ credential: 'fake-google-id-token' }));

    await expect(page).toHaveURL(/\/demo/);
  });
});
