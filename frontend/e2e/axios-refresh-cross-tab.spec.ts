import { test, expect, type Page } from '@playwright/test';
import { step, apiStep } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession } from './support/mockSession';

/**
 * 驗收 N16：refresh 失敗清掉 token 後，過去停在原頁「$ 0 + 載入失敗」；React Query 重試時 refreshToken 已是 null，
 * 又送 3 次 { refreshToken: null }。現在 refresh 失敗 / 沒有 refresh token 一律清 token 並導向 /login?redirect=，
 * refreshToken 為空時不送 refresh，React Query 對 401 不重試。
 *
 * 迴歸 (Backlog 第 6 批 G2)：axiosClient refresh 失敗時過去無條件清 localStorage 的 token。
 * 情境：分頁 A 的 refresh 還在飛，分頁 B 重新登入、把新 token 寫進共用的 localStorage；
 * 單一 session 下 A 手上那顆舊 refresh token 已失效 → refresh 401 → A 把 B 剛拿到的新 token 也清掉，
 * 兩個分頁一起被登出。修正後：清之前比對 localStorage 的 refreshToken 是否仍是本次用的那顆，
 * 已被換掉就不清、改用新 access token 重試原請求。
 */
const NEW_ACCESS = 'tab-b-new-access';
const NEW_REFRESH = 'tab-b-new-refresh';

const ledgerBody = {
  yearMonth: '2026-10',
  totalRevenue: 3800,
  entries: [
    {
      orderId: 'a1023000-0000-0000-0000-0000000000c1',
      ownerName: '跨分頁測試飼主',
      totalAmount: 3800,
      paidAt: '2026-10-01T10:00:00Z',
      completedAt: '2026-10-10T10:00:00Z',
      payoutAt: '2026-10-13T10:00:00Z'
    }
  ]
};

/** 帳務 API 只認分頁 B 的新 token，其餘一律 401（= 舊 session 已被後登入者覆蓋） */
async function mockLedgerRequiringNewToken(page: Page) {
  const authHeaders: string[] = [];
  await page.route('**/api/orders/sitter/ledger**', (route) => {
    const auth = route.request().headers()['authorization'] ?? '';
    authHeaders.push(auth);
    if (auth === `Bearer ${NEW_ACCESS}`) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ledgerBody)
      });
    }
    return route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'UNAUTHORIZED', message: '未認證的請求，請先登入' })
    });
  });
  return authHeaders;
}

const readTokens = (page: Page) =>
  page.evaluate(() => ({
    accessToken: localStorage.getItem('accessToken'),
    refreshToken: localStorage.getItem('refreshToken')
  }));

test.describe('G2 跨分頁 refresh 失敗不清掉別分頁的新 token', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('refresh 進行中另一分頁重新登入：不清新 token，改用新 token 重試原請求並成功渲染', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    const authHeaders = await mockLedgerRequiringNewToken(page);

    // 分頁 B：同一個 browser context（共用 localStorage），開一頁不打 API 的登入頁
    const tabB = await page.context().newPage();
    await tabB.goto('/login');

    const refreshBodies: unknown[] = [];
    await page.route('**/api/auth/refresh', async (route) => {
      refreshBodies.push(route.request().postDataJSON());
      // refresh 飛行途中，分頁 B 重新登入成功、寫入新 token；接著舊 refresh token 被後端拒絕
      await tabB.evaluate(
        ([a, r]) => {
          localStorage.setItem('accessToken', a);
          localStorage.setItem('refreshToken', r);
        },
        [NEW_ACCESS, NEW_REFRESH]
      );
      // 跨分頁 localStorage 是最終一致（不同 renderer 之間非同步廣播），等分頁 A 看得到 B 的寫入再回應，
      // 對應真實情境：B 重新登入早於 A 的 refresh 回應
      await expect
        .poll(() => page.evaluate(() => localStorage.getItem('refreshToken')))
        .toBe(NEW_REFRESH);
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'UNAUTHORIZED', message: 'Refresh token 找不到或無效' })
      });
    });

    await step(
      '分頁 A 開帳務頁：401 → refresh 401，但改用分頁 B 的新 token 重試成功',
      page,
      async () => {
        await page.goto('/sitter/ledger');
        await expect(page.getByTestId('ledger-total-revenue')).toHaveText('$ 3,800');
        await expect(page.getByText('跨分頁測試飼主')).toBeVisible();
        await expect(page.getByText('載入失敗，請稍後再試')).toHaveCount(0);
      }
    );

    await apiStep('token 與請求紀錄：新 token 沒被清、原請求以新 token 重送', async () => {
      const tokens = await readTokens(page);
      expect(tokens).toEqual({ accessToken: NEW_ACCESS, refreshToken: NEW_REFRESH });
      expect(await readTokens(tabB)).toEqual(tokens);
      // refresh 只送一次，送的是分頁 A 原本那顆
      expect(refreshBodies).toEqual([{ refreshToken: 'mock-refresh' }]);
      expect(authHeaders).toEqual(['Bearer mock-sitter-token', `Bearer ${NEW_ACCESS}`]);
      return { tokens, refreshBodies, authHeaders };
    });
  });

  test('對照組：refresh 失敗且 localStorage 仍是同一顆 refresh token → 清除 token 並導向登入頁', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    const authHeaders = await mockLedgerRequiringNewToken(page);
    const refreshBodies: unknown[] = [];
    await page.route('**/api/auth/refresh', (route) => {
      refreshBodies.push(route.request().postDataJSON());
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'UNAUTHORIZED', message: 'Refresh token 找不到或無效' })
      });
    });

    await step(
      '開帳務頁：401 → refresh 401，沒有別的分頁換 token → 清除並導向 /login?redirect=/sitter/ledger',
      page,
      async () => {
        await page.goto('/sitter/ledger');
        // 驗收 N16：過去停在原頁顯示「$ 0」+「載入失敗」，$ 0 容易被誤讀成沒有收入。與 RequireAuth 一致導去登入
        await expect(page).toHaveURL(/\/login\?redirect=%2Fsitter%2Fledger$/);
        await expect(page.getByTestId('login-email-input')).toBeVisible();
        await expect(page.getByTestId('ledger-total-revenue')).toHaveCount(0);
        await expect
          .poll(() => readTokens(page))
          .toEqual({ accessToken: null, refreshToken: null });
      }
    );

    await apiStep(
      'token 已清除、refresh 只送一次（不再帶 null 重送）、帳務 API 從未帶到別的 token',
      async () => {
        const tokens = await readTokens(page);
        expect(tokens).toEqual({ accessToken: null, refreshToken: null });
        expect(refreshBodies).toEqual([{ refreshToken: 'mock-refresh' }]);
        expect(authHeaders).not.toContain(`Bearer ${NEW_ACCESS}`);
        expect(authHeaders[0]).toBe('Bearer mock-sitter-token');
        return { tokens, refreshBodies, authHeaders };
      }
    );
  });

  test('沒有 refresh token：401 不送 /auth/refresh，直接清除 access token 並導向登入頁', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    // 正式登入 session，但 refresh token 已不在（例如被別處清掉）。只在第一次載入時種，導頁後不再寫回
    await page.addInitScript(() => {
      if (sessionStorage.getItem('__e2e_seeded')) return;
      sessionStorage.setItem('__e2e_seeded', '1');
      localStorage.setItem('authMode', 'manual');
      localStorage.setItem('userRole', 'sitter');
      localStorage.setItem('accessToken', 'stale-access');
      localStorage.removeItem('refreshToken');
    });
    const authHeaders = await mockLedgerRequiringNewToken(page);
    const refreshBodies: unknown[] = [];
    await page.route('**/api/auth/refresh', (route) => {
      refreshBodies.push(route.request().postDataJSON());
      return route.fulfill({ status: 400, contentType: 'application/json', body: '{}' });
    });

    await step(
      '開帳務頁：401 → 不送 refresh，導向 /login?redirect=/sitter/ledger',
      page,
      async () => {
        await page.goto('/sitter/ledger');
        await expect(page).toHaveURL(/\/login\?redirect=%2Fsitter%2Fledger$/);
        await expect(page.getByTestId('login-email-input')).toBeVisible();
        await expect
          .poll(() => readTokens(page))
          .toEqual({ accessToken: null, refreshToken: null });
      }
    );

    await apiStep(
      'refresh 0 次（過去送 { refreshToken: null } 並隨 React Query 重試再送 3 次）',
      async () => {
        expect(refreshBodies).toEqual([]);
        expect(authHeaders[0]).toBe('Bearer stale-access');
        return { refreshBodies, authHeaders, tokens: await readTokens(page) };
      }
    );
  });
});
