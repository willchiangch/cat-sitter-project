import { test, expect } from '@playwright/test';

/**
 * PRD-000 AC-6 生物辨識登入 (WebAuthn)。
 *
 * 這支測試刻意「不」mock /api/auth/webauthn/** ——真正的目的是驗證後端 RelyingParty 對
 * 一組真實產生的 WebAuthn 金鑰配對能完成完整的簽章驗證迴圈，這是純粹 mock 掉 API 驗證不到的。
 * 透過 Chrome DevTools Protocol 的 WebAuthn virtual authenticator（Playwright 官方推薦的
 * WebAuthn 測試方式）在瀏覽器層模擬一個真實的生物辨識裝置，實際跑 navigator.credentials.create()/get()。
 *
 * 因此本測試需要一個真正在跑的後端 + 資料庫（非 Testcontainers 短命容器），而非既有 E2E 慣例的全 mock 模式。
 */
test.describe('PRD-000 AC-6 生物辨識登入 (WebAuthn)', () => {
  test.setTimeout(60000);

  test('可在帳號設定頁開啟生物辨識，並用生物辨識重新登入', async ({ page, context }) => {
    const client = await context.newCDPSession(page);
    await client.send('WebAuthn.enable');
    const { authenticatorId } = await client.send('WebAuthn.addVirtualAuthenticator', {
      options: {
        protocol: 'ctap2',
        transport: 'internal',
        hasResidentKey: true,
        hasUserVerification: true,
        isUserVerified: true
      }
    });

    // 1. 用種子飼主帳號密碼登入（真實打後端 /api/auth/login）
    await page.goto('/login');
    await page.getByTestId('login-email-input').fill('owner@test.com');
    await page.getByTestId('login-password-input').fill('password');
    await page.getByTestId('login-submit-btn').click();
    await expect(page).toHaveURL(/\/demo/);

    // 種子帳號是真實、持久的資料庫紀錄（非每次測試都重建的 Testcontainers），
    // 先清掉這個帳號名下所有舊的生物辨識憑證，確保這次測試用的虛擬 authenticator
    // 跟後端 allowCredentials 回傳的憑證清單一致（否則憑證選擇器在 headless 環境下會逾時失敗）
    await page.evaluate(async () => {
      const token = localStorage.getItem('accessToken');
      const res = await fetch('/api/auth/webauthn/credentials', { headers: { Authorization: `Bearer ${token}` } });
      const existing = await res.json();
      for (const credential of existing) {
        await fetch(`/api/auth/webauthn/credentials/${credential.id}`, {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` }
        });
      }
    });

    // 2. 前往帳號設定頁，開啟生物辨識（真實觸發 register/options -> navigator.credentials.create() -> register/verify）
    await page.goto('/account-settings');
    await expect(page.getByTestId('account-settings-webauthn-register-btn')).toBeVisible();
    await page.getByTestId('account-settings-webauthn-register-btn').click();

    await expect(page.getByText(/註冊於/)).toBeVisible({ timeout: 15000 });

    // 3. 登出（清空本機憑證，模擬換裝置/重新打開瀏覽器）
    await page.evaluate(() => {
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      localStorage.removeItem('userRole');
      localStorage.removeItem('authMode');
    });

    // 4. 回登入頁，只填 Email，改用生物辨識登入（真實觸發 login/options -> navigator.credentials.get() -> login/verify）
    await page.goto('/login');
    await page.getByTestId('login-email-input').fill('owner@test.com');
    await expect(page.getByTestId('login-biometric-btn')).toBeVisible();
    await page.getByTestId('login-biometric-btn').click();

    await expect(page).toHaveURL(/\/demo/, { timeout: 15000 });

    await client.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
  });
});
