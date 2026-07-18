import { test, expect } from '@playwright/test';

const sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda';
const questionId = 'q1023000-0000-0000-0000-000000000001';

test.describe('PRD-005 動態問卷與 Zero-Trust 價格核對', () => {
  test.setTimeout(60000);

  let lastBookingBody: any = null;

  test.beforeEach(async ({ page }) => {
    lastBookingBody = null;

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
              dailyCapacity: 5,
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
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'OK', data: 'a1023000-0000-0000-0000-000000000000' })
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
  });
});
