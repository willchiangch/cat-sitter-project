import { test, expect } from '@playwright/test';
import {
  assertNoUnmockedApi,
  mockDemoSession,
  mockOwnerOrderDetail,
  mockOwnerOrderList
} from './support/mockSession';
import { step, trackDialogs, expectDialog } from './support/evidence';

const sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda';
const questionId = 'q1023000-0000-0000-0000-000000000001';
const BOOKED_ORDER_ID = 'a1023000-0000-0000-0000-000000000000';

test.describe('PRD-005 動態問卷與 Zero-Trust 價格核對', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test.setTimeout(60000);

  let lastBookingBody: any = null;
  // 預設照真實契約 201 { orderId }（OrderController.createBooking）；F3 迴歸測試改成舊的錯誤格式
  let bookingResponseBody: object = { orderId: BOOKED_ORDER_ID };

  test.beforeEach(async ({ page }) => {
    // 假 token 打真後端的 header API 會 401 → refresh 失敗清 token → 換頁被 RequireAuth 導去 /login，
    // 共用 mock 先攔通知 / KYC 狀態；頁面 API 仍由本檔 route（後註冊者優先）
    await mockDemoSession(page, 'sitter');
    // 預約頁會查收藏狀態（愛心）；未 mock 時假 token 401 → refresh 失敗清 token
    await page.route('**/api/owner/favorites', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    lastBookingBody = null;
    bookingResponseBody = { orderId: BOOKED_ORDER_ID };
    // N9：送出後導去的詳情頁 / F3 的訂單列表也要 mock，否則截圖停在錯誤畫面
    // 驗收 R6：詳情頁回本檔預約的 $500 方案金額（與 expectedTotalAmount 一致）
    await mockOwnerOrderDetail(page, BOOKED_ORDER_ID, 'PENDING', 500);
    await mockOwnerOrderList(page);

    await page.route(url => url.pathname.includes('/api/auth/login'), async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'fake-token',
          refreshToken: 'fake-refresh',
          userId: '1031efbc-583a-4062-9a35-15706a3384c6',
          email: 'owner@test.com',
          fullName: '愛貓飼主',
          role: 'CLIENT'
        })
      });
    });

    await page.route(url => url.pathname === '/api/pets', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'OK',
          data: [
            {
              id: 'e1023000-0000-0000-0000-000000000001',
              ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
              name: '咪咪',
              species: 'CAT',
              gender: 'FEMALE',
              neutered: true,
              weight: 4.5,
              birthYear: 2021,
              photoUrl: '',
              version: 1
            }
          ]
        })
      });
    });

    await page.route(url => url.pathname === `/api/sitter/profile/${sitterId}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          gated: false,
          sitterId,
          displayName: '測試保母',
          avatarUrl: '',
          bio: '',
          tags: [],
          serviceAreas: [],
          isOpen: true,
          kycStatus: 'VERIFIED',
          version: 1,
          isVisible: true
        })
      });
    });

    await page.route(url => url.pathname.includes('/api/sitters/') && url.pathname.endsWith('/plans'), async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'OK',
          data: [
            {
              id: 'p1023000-0000-0000-0000-000000000001',
              sitterId,
              name: '基礎照顧',
              price: 500,
              defaultTasks: [],
              applicablePetTypes: ['CAT', 'DOG'],
              description: '基礎陪伴照顧',
              startDate: '2026-01-01',
              endDate: '2026-12-31',
              isRestricted: false,
              sortOrder: 0
            }
          ]
        })
      });
    });

    await page.route(url => url.pathname === `/api/sitters/${sitterId}/questions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: questionId,
            questionText: '毛孩是否會攻擊人？',
            answerType: 'RADIO',
            options: ['會', '不會'],
            required: true,
            sortOrder: 0,
            isActive: true
          }
        ])
      });
    });

    await page.route(url => url.pathname === '/api/orders/booking', async (route) => {
      lastBookingBody = route.request().postDataJSON();
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(bookingResponseBody)
      });
    });

    await page.goto('/');
  });

  test('Step 2 呈現保母的動態問卷，必填未答時擋下送出，答題後送出正確帶入答案與試算金額', async ({ page }) => {
    const roleText = await page.locator('p').filter({ hasText: '當前角色:' }).textContent();
    if (roleText?.includes('貓咪保母')) {
      await page.click('[data-testid="btn-role-toggle"]');
    }
    await page.click('text=進入預約精靈 (飼主端)');

    await expect(page.getByRole('heading', { name: '選擇服務方案' })).toBeVisible();
    await page.click('[data-testid="client-booking-plan-card-0"]');

    await expect(page.getByRole('heading', { name: '排程配置' })).toBeVisible();

    const today = new Date();
    const targetDate = new Date(today);
    targetDate.setDate(today.getDate() + 1);
    const dateStr = targetDate.toISOString().split('T')[0];
    await page.click(`[data-testid="client-booking-date-0-0-${dateStr}"]`);
    await page.click('[data-testid="client-booking-btn-confirm-date-0-0"]');

    await page.click('[data-testid="client-booking-btn-step1-next"]');

    // 進入 Step 2 後應看到動態問卷
    await expect(page.getByRole('heading', { name: '預約摘要' })).toBeVisible();
    await expect(page.getByTestId('client-booking-questionnaire')).toBeVisible();
    await expect(page.getByTestId(`booking-question-${questionId}`)).toContainText('毛孩是否會攻擊人？');

    // 未答必填題時擋下送出
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('毛孩是否會攻擊人？');
      await dialog.accept();
    });
    await page.getByTestId('client-booking-btn-submit').click();
    expect(lastBookingBody).toBeNull();

    // 作答後可正常送出，且答案與試算金額正確帶入
    await page.getByTestId(`booking-question-${questionId}-option-不會`).click();
    await page.getByTestId('client-booking-btn-submit').click();

    await expect.poll(() => lastBookingBody).not.toBeNull();
    expect(lastBookingBody.answers).toEqual([
      { questionId, answerValues: ['不會'] }
    ]);
    expect(lastBookingBody.expectedTotalAmount).toBe(500);

    await step('送出後導向新訂單詳情頁 /owner/orders/{orderId}（$ 500）', page, async () => {
      await expect(page).toHaveURL(new RegExp(`/owner/orders/${BOOKED_ORDER_ID}$`));
      // 詳情頁已 mock：截圖要看到剛建立的訂單（待評估），不是錯誤畫面
      await expect(page.getByTestId('order-status-badge')).toHaveText('待評估');
      await expect(page.getByTestId('order-total-amount')).toHaveText('$ 500');
    });
  });

  test('迴歸 (F3)：預約回應缺 orderId 時明確提示並導向訂單列表，不導去 /owner/orders/[object Object]', async ({
    page
  }) => {
    // 舊 mock 的錯誤格式：過去 `res.orderId || res` 會把整個物件塞進網址
    bookingResponseBody = { code: 200, message: 'OK', data: BOOKED_ORDER_ID };
    const dialogs = trackDialogs(page);
    const orderDetailRequests: string[] = [];
    page.on('request', (req) => {
      const { pathname } = new URL(req.url());
      if (pathname.startsWith('/api/orders/') && pathname !== '/api/orders/booking') {
        orderDetailRequests.push(pathname);
      }
    });

    await step('進入預約摘要並作答', page, async () => {
      const roleText = await page.locator('p').filter({ hasText: '當前角色:' }).textContent();
      if (roleText?.includes('貓咪保母')) {
        await page.getByTestId('btn-role-toggle').click();
      }
      await page.click('text=進入預約精靈 (飼主端)');
      await expect(page.getByRole('heading', { name: '選擇服務方案' })).toBeVisible();
      await page.getByTestId('client-booking-plan-card-0').click();
      await expect(page.getByRole('heading', { name: '排程配置' })).toBeVisible();
      const target = new Date();
      target.setDate(target.getDate() + 1);
      const dateStr = target.toISOString().split('T')[0];
      await page.getByTestId(`client-booking-date-0-0-${dateStr}`).click();
      await page.getByTestId('client-booking-btn-confirm-date-0-0').click();
      await page.getByTestId('client-booking-btn-step1-next').click();
      await expect(page.getByRole('heading', { name: '預約摘要' })).toBeVisible();
      await page.getByTestId(`booking-question-${questionId}-option-不會`).click();
      await expect(page.getByTestId(`booking-question-${questionId}-option-不會`)).toBeChecked();
    });

    await step('送出：明確提示無法取得訂單編號並導向訂單列表', page, async () => {
      await page.getByTestId('client-booking-btn-submit').click();
      await expectDialog(dialogs, /^預約已送出，但無法取得訂單編號，請到「我的訂單」確認預約狀態$/);
      await expect(page).toHaveURL(/\/owner\/orders$/);
      expect(lastBookingBody).not.toBeNull();
      expect(orderDetailRequests.filter((p) => p.includes('object'))).toEqual([]);
    });
  });
});
