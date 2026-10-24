import { test, expect } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockSessionApis } from './support/mockSession';

test.describe('PRD-000 AC-8 帳號註銷（軟刪除）流程', () => {
  test.setTimeout(60000);
  // 驗收 N16 起 session 失效（refresh 失敗）會導去 /login：過去這支用假 token 'fake' 進站，AppHeader 的通知 /
  // KYC API 打到後端 401 → refresh 401 → token 被清，但頁面停在原地所以照樣綠（實際上是未登入狀態）。
  // 改接共用的 header API mock + 漏 mock 偵測（N9）
  test.beforeEach(async ({ page }) => {
    await mockSessionApis(page);
    // 帳號設定頁的生物辨識區塊會列出已綁定的憑證
    await page.route('**/api/auth/webauthn/credentials', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    );
  });
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  async function loginViaRegisterFlow(page: import('@playwright/test').Page, email: string) {
    await page.route('**/api/auth/register', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OTP_SENT', email })
      });
    });
    await page.route('**/api/auth/register/verify-otp', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'fake',
          refreshToken: 'fake',
          userId: '1',
          email,
          role: 'OWNER'
        })
      });
    });

    await page.goto('/register');
    await page.getByTestId('register-fullname-input').fill('註銷測試使用者');
    await page.getByTestId('register-email-input').fill(email);
    await page.getByTestId('register-password-input').fill('password123');
    await page.getByTestId('register-terms-checkbox').check();
    await page.getByTestId('register-submit-btn').click();
    await page.getByTestId('register-otp-input').fill('123456');
    await page.getByTestId('register-otp-submit-btn').click();
    await expect(page).toHaveURL(/\/demo/);
  }

  test('輸入正確密碼可成功註銷帳號並導回登入頁', async ({ page }) => {
    await loginViaRegisterFlow(page, 'deactivate-e2e@test.com');

    await page.route('**/api/auth/deactivate', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '帳號已成功註銷' })
      });
    });

    await step('帳號設定頁開啟註銷確認', page, async () => {
      await page.goto('/account-settings');
      await page.getByTestId('account-settings-deactivate-btn').click();
      await page.getByTestId('account-settings-deactivate-password-input').fill('password123');
      await expect(page.getByTestId('account-settings-deactivate-confirm-btn')).toBeVisible();
    });

    await step('確認註銷後導回登入頁、token 已清除', page, async () => {
      await page.getByTestId('account-settings-deactivate-confirm-btn').click();
      await expect(page).toHaveURL(/\/login/);
      const accessToken = await page.evaluate(() => window.localStorage.getItem('accessToken'));
      expect(accessToken).toBeNull();
    });
  });

  test('尚有未結案訂單時應顯示卡控訊息且不導頁', async ({ page }) => {
    await loginViaRegisterFlow(page, 'deactivate-blocked-e2e@test.com');

    await page.route('**/api/auth/deactivate', async (route) => {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'ACCOUNT_DEACTIVATION_BLOCKED',
          message: '您尚有未結案的訂單，請先完成或取消後再嘗試註銷帳號'
        })
      });
    });

    await step('有未結案訂單：顯示卡控訊息、留在帳號設定頁', page, async () => {
      await page.goto('/account-settings');
      await page.getByTestId('account-settings-deactivate-btn').click();
      await page.getByTestId('account-settings-deactivate-password-input').fill('password123');
      await page.getByTestId('account-settings-deactivate-confirm-btn').click();
      await expect(page.getByTestId('account-settings-deactivate-error')).toHaveText(
        '您尚有未結案的訂單，請先完成或取消後再嘗試註銷帳號'
      );
      await expect(page).toHaveURL(/\/account-settings/);
    });
  });
});
