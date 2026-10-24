import { test, expect, type Page } from '@playwright/test';
import { step, apiStep } from './support/evidence';
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
    // 證據稽核（.e2e-runs/20261024-1939）：過去點擊前後畫面一樣，「沒有導覽」只能從 manifest 的 url 推。
    // 這裡讓外部連結通知是未讀：點擊會先 await POST /read、再決定要不要導覽，等 read 完成後列表重抓回來，
    // 就代表「導覽與否」已經執行完，才斷言 URL 沒變（不用固定秒數等待）。
    // 驗收 R4：列表 mock 有狀態，POST /read 之後重抓回 isRead:true，截圖上的未讀藍點與色條才會消失
    let externalRead = false;
    await page.route(/\/api\/notifications(\?.*)?$/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            content: notifications.map((n) =>
              n.id === 'n-external' ? { ...n, isRead: externalRead } : n
            ),
            page: 0,
            size: 20,
            totalElements: 5,
            totalPages: 1
          }
        })
      })
    );
    const readCalls: string[] = [];
    await page.route('**/api/notifications/n-external/read', (route) => {
      readCalls.push(route.request().method());
      externalRead = true;
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });
    const externalRequests: string[] = [];
    page.on('request', (r) => {
      if (new URL(r.url()).hostname === 'evil.example.com') externalRequests.push(r.url());
    });
    const mainFrameNavigations: string[] = [];
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) mainFrameNavigations.push(frame.url());
    });

    const before = await step('點擊前：通知中心列出外部連結通知（未讀）', page, async () => {
      await page.goto('/notifications');
      await expect(page.getByTestId('client-notifications-item-n-external')).toBeVisible();
      await expect(page.getByTestId('client-notifications-unread-dot-n-external')).toHaveCount(1);
      await expect(page).toHaveURL(/\/notifications$/);
      return {
        url: page.url(),
        historyLength: await page.evaluate(() => history.length),
        navigations: mainFrameNavigations.length
      };
    });

    const after = await step(
      '點擊後：標為已讀，但 URL 仍是 /notifications、沒有導覽',
      page,
      async () => {
        const refetched = page.waitForResponse(
          (r) =>
            /\/api\/notifications(\?.*)?$/.test(new URL(r.url()).pathname) &&
            r.request().method() === 'GET'
        );
        await page.getByTestId('client-notifications-item-n-external').click();
        await expect.poll(() => readCalls).toEqual(['POST']);
        await refetched;
        await expect(page.getByTestId('client-notifications-list')).toBeVisible();
        // 重抓回 isRead:true：未讀藍點消失、左側色條變透明（截圖上看得到「已讀」）
        await expect(page.getByTestId('client-notifications-unread-dot-n-external')).toHaveCount(0);
        await expect(page.getByTestId('client-notifications-item-n-external')).toHaveCSS(
          'border-left-color',
          'rgba(0, 0, 0, 0)'
        );
        await expect(page).toHaveURL(/\/notifications$/);
        return {
          url: page.url(),
          historyLength: await page.evaluate(() => history.length),
          navigations: mainFrameNavigations.length
        };
      }
    );

    await apiStep(
      '點擊前後 URL、history 長度、主框架導覽次數不變，沒有對外部主機發出請求',
      async () => {
        expect(after.url).toBe(before.url);
        expect(after.historyLength).toBe(before.historyLength);
        expect(after.navigations).toBe(before.navigations);
        expect(externalRequests).toEqual([]);
        return { before, after, readCalls, externalRequests, mainFrameNavigations };
      }
    );
  });
});
