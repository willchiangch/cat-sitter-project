import { test, expect } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockSessionApis } from './support/mockSession';

test.describe('PRD-000 註冊與忘記密碼流程', () => {
  test.setTimeout(60000);
  // 驗收 N16 起 session 失效（refresh 失敗）會導去 /login：登入成功後回的是假 token，AppHeader 的通知 / KYC API
  // 打到後端 401 → refresh 401 → 被導回 /login?redirect=%2Fdemo（正式 build 上比斷言 /demo 還快）。
  // 改接共用的 header API mock + 漏 mock 偵測（N9）
  test.beforeEach(async ({ page }) => {
    await mockSessionApis(page);
  });
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('可從登入頁前往註冊頁，送出表單後輸入 OTP 完成驗證並登入', async ({ page }) => {
    await page.route('**/api/auth/register', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OTP_SENT', email: 'new@test.com' })
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
          email: 'new@test.com',
          role: 'OWNER'
        })
      });
    });

    await page.goto('/login');
    await page.getByTestId('login-register-link').click();
    await expect(page).toHaveURL(/\/register$/);

    await page.getByTestId('register-fullname-input').fill('新使用者');
    await page.getByTestId('register-email-input').fill('new@test.com');
    await page.getByTestId('register-password-input').fill('password123');
    await expect(page.getByTestId('register-submit-btn')).toBeDisabled();
    await page.getByTestId('register-terms-checkbox').check();
    await page.getByTestId('register-submit-btn').click();

    await expect(page.getByTestId('register-otp-sent-banner')).toBeVisible();

    await step('輸入 OTP 完成驗證，登入並停在 Demo 首頁', page, async () => {
      await page.getByTestId('register-otp-input').fill('123456');
      await page.getByTestId('register-otp-submit-btn').click();
      await expect(page).toHaveURL(/\/demo$/);
      await expect(page.getByText('當前角色:')).toBeVisible();
    });
  });

  test('註冊表單送出後，OTP 重寄冷卻期間應無法再次點擊重寄', async ({ page }) => {
    await page.route('**/api/auth/register', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OTP_SENT', email: 'resend@test.com' })
      });
    });

    await page.goto('/register');
    await page.getByTestId('register-fullname-input').fill('重寄測試');
    await page.getByTestId('register-email-input').fill('resend@test.com');
    await page.getByTestId('register-password-input').fill('password123');
    await page.getByTestId('register-terms-checkbox').check();
    await page.getByTestId('register-submit-btn').click();

    await expect(page.getByTestId('register-otp-sent-banner')).toBeVisible();
    await expect(page.getByTestId('register-otp-resend-btn')).toBeDisabled();
  });

  test('可從登入頁前往忘記密碼頁並送出申請', async ({ page }) => {
    await page.route('**/api/auth/forgot-password', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'SUCCESS',
          message: '若該信箱已註冊，重設密碼連結將寄送至該信箱'
        })
      });
    });

    await page.goto('/login');
    await page.getByTestId('login-forgot-password-link').click();
    await expect(page).toHaveURL(/\/forgot-password$/);

    await page.getByTestId('forgot-password-email-input').fill('someone@test.com');
    await page.getByTestId('forgot-password-submit-btn').click();

    await expect(page.getByTestId('forgot-password-success')).toBeVisible();
  });

  test('重設密碼頁帶有效 token 時可成功提交新密碼', async ({ page }) => {
    await page.route('**/api/auth/reset-password', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '密碼已成功重設，請重新登入' })
      });
    });

    await page.goto('/reset-password?token=fake-token-123');
    await page.getByTestId('reset-password-new-password-input').fill('newpassword456');
    await page.getByTestId('reset-password-submit-btn').click();

    await expect(page.getByTestId('reset-password-success')).toBeVisible();
  });

  test('重設密碼頁缺少 token 時應提示重新申請', async ({ page }) => {
    await page.goto('/reset-password');
    await expect(page.getByRole('link', { name: '重新申請' })).toBeVisible();
  });
});
