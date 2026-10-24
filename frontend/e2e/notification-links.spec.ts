import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';
import {
  assertNoUnmockedApi,
  mockDemoSession,
  mockOwnerOrderDetail,
  mockOwnerOrderList
} from './support/mockSession';

/**
 * 迴歸 (Backlog 第 3 批 D1)：點通知要導到前端真的存在的路由。
 * 過去後端寫 /client/orders、/visits/report?orderId=（routes.tsx 沒有這兩條），前端只靠三個寫死字串
 * 轉址（/sitter/orders、/client/orders → /owner/orders、/sitter/kyc），其他連結（推薦通知 /booking/{id}、
 * 影像到期提醒）點了完全沒反應。現在後端直接寫真實路由（NotificationListenerLinkTest 比對 routes.tsx），
 * 前端 resolveNotificationLink() 原樣導覽，2026-10 前的舊通知在前端轉換。
 */
const orderId = 'a1023000-0000-0000-0000-00000000d1d1';
const sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda';

const notifications = [
  { id: 'n-new', linkUrl: `/owner/orders/${orderId}`, title: '付款已確認' },
  { id: 'n-legacy-orders', linkUrl: '/client/orders', title: '舊通知：付款憑證退回' },
  {
    id: 'n-legacy-report',
    linkUrl: `/visits/report?orderId=${orderId}`,
    title: '舊通知：影像即將過期'
  },
  { id: 'n-referral', linkUrl: `/booking/${sitterId}`, title: '保母推薦' },
  { id: 'n-external', linkUrl: '//evil.example.com/phish', title: '外部連結不可導覽' }
].map((n) => ({
  ...n,
  content: n.title,
  category: 'ORDER_AFFAIR',
  isRead: true,
  createdAt: '2026-10-24T00:00:00Z',
  roleTarget: 'OWNER'
}));

async function mockOwnerSession(page: Page) {
  // 驗收 R1：過去自己寫一份 session mock、沒攔訂單詳情，假 token 打到後端 401 → refresh 401，
  // 落地截圖是紅字「Refresh token 找不到或無效」。改用共用 mockDemoSession（含漏 mock 偵測）
  await mockDemoSession(page, 'client');
  // 覆蓋共用的空通知清單（後註冊者優先）
  await page.route(/\/api\/notifications(\?.*)?$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { content: notifications, page: 0, size: 20, totalElements: 5, totalPages: 1 }
      })
    })
  );
  await mockOwnerOrderDetail(page, orderId, 'CONFIRMED');
  await mockOwnerOrderList(page);
  await mockReferralBookingPage(page);
}

/** 推薦通知落地的預約頁（/booking/{sitterId}）：公開檔案、方案、問卷、收藏、寵物 */
async function mockReferralBookingPage(page: Page) {
  const json = (body: unknown) => ({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body)
  });
  await page.route(
    (url) => url.pathname === `/api/sitter/profile/${sitterId}`,
    (route) =>
      route.fulfill(
        json({
          gated: false,
          sitterId,
          displayName: '推薦保母',
          avatarUrl: '',
          bio: '',
          tags: [],
          serviceAreas: [],
          isOpen: true,
          kycStatus: 'VERIFIED',
          version: 1,
          isVisible: true
        })
      )
  );
  await page.route(
    (url) => url.pathname === `/api/sitters/${sitterId}/plans`,
    (route) =>
      route.fulfill(
        json({
          data: [
            {
              id: 'p1023000-0000-0000-0000-0000000000d1',
              sitterId,
              name: '基礎照顧',
              description: '餵食與陪玩',
              price: 600,
              durationMinutes: 60,
              dailyCapacity: 2,
              applicablePetTypes: ['CAT'],
              defaultTasks: [],
              isActive: true
            }
          ]
        })
      )
  );
  await page.route(
    (url) => url.pathname === `/api/sitters/${sitterId}/questions`,
    (route) => route.fulfill(json([]))
  );
  await page.route('**/api/owner/favorites', (route) => route.fulfill(json([])));
  await page.route(
    (url) => url.pathname === '/api/pets',
    (route) => route.fulfill(json({ data: [] }))
  );
}

test.describe('通知連結導向真實路由 (D1 迴歸)', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  // landed：導頁後先斷言落地頁的業務內容已渲染再截圖（驗收 R1：只斷言 URL，截圖卻是錯誤畫面）
  const cases: {
    id: string;
    expected: RegExp;
    label: string;
    landed: (page: Page) => Promise<void>;
  }[] = [
    {
      id: 'n-new',
      expected: new RegExp(`/owner/orders/${orderId}$`),
      label: '新通知 /owner/orders/{id}',
      landed: (page) => expect(page.getByTestId('order-status-badge')).toHaveText('已確認')
    },
    {
      id: 'n-legacy-orders',
      expected: /\/owner\/orders$/,
      label: '舊通知 /client/orders → /owner/orders',
      landed: (page) => expect(page.getByText('目前沒有相關訂單')).toBeVisible()
    },
    {
      id: 'n-legacy-report',
      expected: new RegExp(`/owner/orders/${orderId}$`),
      label: '舊通知 /visits/report?orderId= → /owner/orders/{id}',
      landed: (page) => expect(page.getByTestId('order-status-badge')).toHaveText('已確認')
    },
    {
      id: 'n-referral',
      expected: new RegExp(`/booking/${sitterId}$`),
      label: '推薦通知 /booking/{id}',
      landed: (page) => expect(page.getByRole('heading', { name: '選擇服務方案' })).toBeVisible()
    }
  ];

  for (const c of cases) {
    test(`通知中心點擊：${c.label}`, async ({ page }) => {
      await mockOwnerSession(page);
      await step('開通知中心', page, async () => {
        await page.goto('/notifications');
        await expect(page.getByTestId(`client-notifications-item-${c.id}`)).toBeVisible();
      });
      await step(`點擊通知 ${c.id}`, page, async () => {
        await page.getByTestId(`client-notifications-item-${c.id}`).click();
        await expect(page).toHaveURL(c.expected);
        await c.landed(page);
      });
    });
  }

  test('通知中心點擊：非同源相對路徑（//host）不導覽', async ({ page }) => {
    await mockOwnerSession(page);
    await step('開通知中心並點外部連結通知', page, async () => {
      await page.goto('/notifications');
      const item = page.getByTestId('client-notifications-item-n-external');
      await expect(item).toBeVisible();
      await item.click();
      // 點擊處理是 async（先 markAsRead 再導覽）；等列表重新渲染後 URL 仍是 /notifications
      await expect(page.getByTestId('client-notifications-list')).toBeVisible();
      await expect(page).toHaveURL(/\/notifications$/);
    });
  });
});
