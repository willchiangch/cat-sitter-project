import path from 'path';
import { fileURLToPath } from 'url';
import { expect, type Page } from '@playwright/test';

export interface MockSession {
  /** 沒有被任何 page.route 接手、落到 context 層 catch-all 的 /api/** 請求（`METHOD /path?query`） */
  unmockedApi: string[];
  /**
   * 斷言本測試沒有漏 mock 的 API。每支用 mockDemoSession 的 spec 都必須呼叫（e2e:lint 檢查），
   * 建議放 test.afterEach 或每個 test 結尾。
   */
  assertNoUnmockedApi: () => void;
}

const UNMOCKED_STATUS = 501;
const sessions = new WeakMap<Page, MockSession>();

/**
 * 給 `test.afterEach(({ page }) => assertNoUnmockedApi(page))` 用：該 page 沒呼叫過 mockSessionApis /
 * mockDemoSession 就略過。e2e:lint 規定：用到這兩者的 spec 必須呼叫 assertNoUnmockedApi。
 */
export function assertNoUnmockedApi(page: Page) {
  sessions.get(page)?.assertNoUnmockedApi();
}

/**
 * 已登入頁面共用的 API mock + 漏 mock 偵測，不動 localStorage、不攔 login（給自己控制登入流程的 spec 用，
 * 例如 seed-auto-login-scope 要從匿名狀態開始數 login 次數）。一般 spec 請用 {@link mockDemoSession}。
 *
 * 攔截 AppHeader 每頁都會打的通知 / KYC 狀態 API，避免假 token 打到真後端 401 → refresh 失敗 → token 被清掉。
 * 頁面本身的 API 由各 spec 自己 mock。
 *
 * 漏 mock 偵測（驗收 N9）：CI 的 mock spec 是對正式站跑的，過去漏 mock 的 API 會帶假 token 打到正式後端，
 * 401 → /api/auth/refresh 401/400 → token 被清，畫面變錯誤頁卻沒有任何斷言失敗。現在：
 * - context 層註冊 `/api/` catch-all（page.route 一律優先於 context.route，不管註冊順序），
 *   沒被 page.route 接手的請求一律回 501 `E2E_UNMOCKED_API`、記進 unmockedApi，不會送到任何後端
 * - `/api/auth/refresh` 也攔下回 401 `E2E_MOCK_REFRESH` 並記錄：mock spec 走到 refresh 代表有 API 回了 401，
 *   需要測 refresh 行為的 spec 請自行 page.route 覆蓋（後註冊者優先）
 * - assertNoUnmockedApi() 斷言兩者皆為空
 */
export async function mockSessionApis(page: Page): Promise<MockSession> {
  const unmockedApi: string[] = [];
  const describe = (url: string, method: string) => {
    const u = new URL(url);
    return `${method} ${u.pathname}${u.search}`;
  };
  // 用 pathname 判斷：regex /\/api\// 會誤攔 vite dev server 的 /src/api/*.ts 模組
  await page.context().route(
    (url) => url.pathname.startsWith('/api/'),
    (route) => {
      const req = route.request();
      const what = describe(req.url(), req.method());
      unmockedApi.push(what);
      return route.fulfill({
        status: UNMOCKED_STATUS,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'E2E_UNMOCKED_API', message: `mock spec 未 mock：${what}` })
      });
    }
  );
  await page.route('**/api/auth/refresh', (route) => {
    unmockedApi.push(describe(route.request().url(), route.request().method()));
    return route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        error: 'E2E_MOCK_REFRESH',
        message: 'mock spec 走到 refresh：有 API 回了 401，請檢查 mock'
      })
    });
  });
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
  // 保母角色 AppHeader 會查 KYC 狀態（橫幅）；各 spec 需要別的狀態時自行再 route（後註冊者優先）
  await page.route('**/api/sitter/kyc/status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { kycStatus: 'VERIFIED', rejectReason: '', submittedAt: '' } })
    })
  );
  const session: MockSession = {
    unmockedApi,
    assertNoUnmockedApi: () =>
      expect(unmockedApi, '以下 /api 請求沒有被 mock（catch-all 回 501）').toEqual([])
  };
  sessions.set(page, session);
  return session;
}

/**
 * mock E2E 共用：以 Demo 模式 (authMode=seed) 進站，種子登入回假 token，其餘同 {@link mockSessionApis}
 * （header API mock + 漏 mock 偵測）。
 */
export async function mockDemoSession(
  page: Page,
  role: 'sitter' | 'client' | 'admin'
): Promise<MockSession> {
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
  return mockSessionApis(page);
}

/**
 * 飼主訂單詳情頁（/owner/orders/{id}）的最小 mock：訂單本體 + 行程清單。
 * 導頁到詳情的 spec（預約送出、通知連結）都要接，否則 catch-all 501、截圖停在錯誤畫面（驗收 R1）。
 * totalAmount 請傳實際預約的方案金額（驗收 R6：過去寫死 600，預約 $500 方案的截圖卻顯示 $600）。
 */
export async function mockOwnerOrderDetail(
  page: Page,
  orderId: string,
  status = 'PENDING',
  totalAmount = 600
) {
  await page.route(`**/api/orders/${orderId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: orderId,
        ownerId: 'o-1',
        sitterId: 's-1',
        status,
        totalAmount,
        amountDue: totalAmount,
        adjustmentAmount: 0,
        disclaimerAgreed: true,
        hasPaymentProof: false,
        paymentProofLastFive: null,
        items: [],
        sitterPaymentInfo: null
      })
    })
  );
  await page.route(`**/api/orders/${orderId}/visits`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: 'v-1', status: 'PENDING', scheduledAt: '2026-12-01T00:00:00Z' }])
    })
  );
}

/** 飼主訂單列表（/owner/orders）mock，預設空清單 */
export async function mockOwnerOrderList(page: Page, orders: object[] = []) {
  await page.route(
    (url) => url.pathname === '/api/orders/owner',
    (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(orders) })
  );
}

const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), '../journeys/fixtures');

/** 把一個假的簽名網址接到本機 fixture 圖片（GCS 簽名網址的替身） */
export async function serveFixtureImage(page: Page, url: string, fixture: string) {
  await page.route(url, (route) =>
    route.fulfill({ status: 200, contentType: 'image/jpeg', path: path.join(FIXTURES, fixture) })
  );
}
