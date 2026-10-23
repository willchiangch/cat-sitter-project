import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';

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
  await page.addInitScript(() => {
    localStorage.setItem('authMode', 'seed');
    localStorage.setItem('userRole', 'client');
  });
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ accessToken: 'mock-owner-token', refreshToken: 'mock-refresh' })
    })
  );
  await page.route('**/api/notifications/unread-count**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"data":0}' })
  );
  await page.route(/\/api\/notifications(\?.*)?$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { content: notifications, page: 0, size: 20, totalElements: 5, totalPages: 1 }
      })
    })
  );
}

test.describe('通知連結導向真實路由 (D1 迴歸)', () => {
  const cases: { id: string; expected: RegExp; label: string }[] = [
    {
      id: 'n-new',
      expected: new RegExp(`/owner/orders/${orderId}$`),
      label: '新通知 /owner/orders/{id}'
    },
    {
      id: 'n-legacy-orders',
      expected: /\/owner\/orders$/,
      label: '舊通知 /client/orders → /owner/orders'
    },
    {
      id: 'n-legacy-report',
      expected: new RegExp(`/owner/orders/${orderId}$`),
      label: '舊通知 /visits/report?orderId= → /owner/orders/{id}'
    },
    {
      id: 'n-referral',
      expected: new RegExp(`/booking/${sitterId}$`),
      label: '推薦通知 /booking/{id}'
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
