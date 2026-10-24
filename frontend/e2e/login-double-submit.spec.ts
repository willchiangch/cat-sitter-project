import { test, expect, type Page, type Route } from '@playwright/test';
import { step, apiStep } from './support/evidence';

/**
 * 迴歸 (Backlog 第 5 批 F1)：後端一個帳號只留一組 refresh token（SD-000 單一 session，後登入者覆蓋）。
 * 登入頁雙擊 / 送出中再按 Enter / Google callback 重入，都會送出第二次登入，把剛寫進 localStorage 的
 * refresh token 在後端覆蓋掉。過去 finally 一律 setLoading(false)，成功後換頁前按鈕又變回可按。
 */
const fakeJwt = (role: string) => {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'real-user@example.com', role, userId: 'u-1' })}.sig`;
};

/** 讓 API 卡住直到測試放行，用來觀察「送出中」狀態；回傳呼叫次數與放行函式 */
async function holdApi(page: Page, url: string, body: object) {
  const held: Route[] = [];
  let release: () => void = () => {};
  const released = new Promise<void>((resolve) => (release = resolve));
  await page.route(url, async (route) => {
    held.push(route);
    await released;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body)
    });
  });
  return { held, release };
}

/**
 * 攔下登入成功後的整頁導覽並回 204：瀏覽器收到 204 不會換頁，登入頁留在原地，
 * 等同把「成功後、換頁前」那一刻凍結住，按鈕狀態才斷言得到（卡住 document 請求不行：
 * 導覽 pending 時 page.evaluate 會一起卡住）。回傳導覽次數。
 */
async function captureNavigation(page: Page, pathname: string) {
  const navigations: string[] = [];
  await page.route(
    (url) => url.pathname === pathname,
    async (route) => {
      if (route.request().resourceType() !== 'document') return route.continue();
      navigations.push(route.request().url());
      await route.fulfill({ status: 204, body: '' });
    }
  );
  return navigations;
}

test.describe('F1 登入送出中防重複送出', () => {
  test('帳密登入：雙擊與送出中按 Enter 只送一次 login，成功後換頁前按鈕維持 disabled', async ({
    page
  }) => {
    const login = await holdApi(page, '**/api/auth/login', {
      accessToken: fakeJwt('OWNER'),
      refreshToken: 'refresh-first'
    });
    const navigations = await captureNavigation(page, '/register');

    await step('開啟登入頁並填入帳密', page, async () => {
      await page.goto('/login?redirect=%2Fregister');
      await page.getByTestId('login-email-input').fill('real-user@example.com');
      await page.getByTestId('login-password-input').fill('password123');
      await expect(page.getByTestId('login-submit-btn')).toBeEnabled();
    });

    await step('雙擊登入、送出中再按 Enter：按鈕 disabled 且只送出一次', page, async () => {
      await page.getByTestId('login-submit-btn').dblclick();
      await expect(page.getByTestId('login-submit-btn')).toBeDisabled();
      await expect(page.getByTestId('login-submit-btn')).toHaveText('登入中...');
      await page.getByTestId('login-password-input').press('Enter');
      await expect.poll(() => login.held.length).toBe(1);
    });

    await step('登入成功、換頁前：按鈕仍 disabled，不會被再按一次', page, async () => {
      login.release();
      await expect.poll(() => navigations.length).toBe(1);
      await expect
        .poll(() => page.evaluate(() => localStorage.getItem('refreshToken')))
        .toBe('refresh-first');
      await expect(page.getByTestId('login-submit-btn')).toBeDisabled();
      await expect(page.getByTestId('login-submit-btn')).toHaveText('登入中...');
    });

    await apiStep('整段流程 POST /api/auth/login 只有 1 次、導向 redirect 目標 1 次', async () => {
      expect(login.held).toHaveLength(1);
      expect(navigations).toEqual([expect.stringMatching(/\/register$/)]);
      expect(login.held[0].request().postDataJSON()).toEqual({
        email: 'real-user@example.com',
        password: 'password123'
      });
      return { loginCalls: login.held.length, navigations };
    });
  });

  test('Google 登入：callback 重入只送一次，送出中 Google 按鈕不可點且顯示登入中', async ({
    page
  }) => {
    const google = await holdApi(page, '**/api/auth/google', {
      status: 'SUCCESS',
      accessToken: fakeJwt('OWNER'),
      refreshToken: 'refresh-google',
      userId: 'u-1',
      role: 'OWNER'
    });

    await step('開啟登入頁', page, async () => {
      await page.goto('/login?redirect=%2Fregister');
      await page.waitForFunction(
        () => typeof (window as any).__handleGoogleCredential === 'function'
      );
      await expect(page.getByTestId('google-signin-button')).toHaveAttribute(
        'aria-disabled',
        'false'
      );
    });

    await step('同一時間收到兩次 Google credential：只送一次，按鈕區塊停用', page, async () => {
      await page.evaluate(() => {
        const handle = (window as any).__handleGoogleCredential;
        handle({ credential: 'google-id-token-1' });
        handle({ credential: 'google-id-token-2' });
      });
      await expect(page.getByTestId('google-signin-button')).toHaveAttribute(
        'aria-disabled',
        'true'
      );
      await expect(page.getByTestId('google-signin-button')).toHaveCSS('pointer-events', 'none');
      await expect(page.getByTestId('google-signin-loading')).toHaveText('Google 登入中...');
      await expect(page.getByTestId('login-submit-btn')).toBeDisabled();
      await expect.poll(() => google.held.length).toBe(1);
    });

    await step('放行：導向 redirect 目標', page, async () => {
      google.release();
      await expect(page).toHaveURL(/\/register$/);
    });

    await apiStep('POST /api/auth/google 只有 1 次，送的是第一個 credential', async () => {
      expect(google.held).toHaveLength(1);
      expect(google.held[0].request().postDataJSON()).toEqual({ idToken: 'google-id-token-1' });
      return { googleCalls: google.held.length };
    });
  });
});
