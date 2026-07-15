import { test, expect } from '@playwright/test';

test.describe('Order Evaluation Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('should toggle role and change theme color', async ({ page }) => {
    const title = page.locator('h1');
    const toggleBtn = page.getByTestId('btn-role-toggle');

    // Default is Sitter (Stitch Amber)
    await expect(title).toHaveCSS('color', 'rgb(118, 86, 0)');

    await toggleBtn.click();
    // Switched to Client (Stitch Blue)
    await expect(title).toHaveCSS('color', 'rgb(0, 94, 159)');
  });
});

test.describe('SD-006 保母訂單評估頁 E2E 測試', () => {
  const orderId = 'a1023000-0000-0000-0000-000000000000';
  let orderStatus = 'PENDING';
  let orderVersion = 0;

  test.beforeEach(async ({ page }) => {
    orderStatus = 'PENDING';
    orderVersion = 0;

    await page.route(`**/api/orders/${orderId}`, async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: orderId,
          ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
          ownerName: '陳先生',
          sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
          status: orderStatus,
          totalAmount: 2400,
          version: orderVersion,
          adjustmentAmount: 0,
          items: [
            {
              category: 'BOOKING',
              serviceName: '基礎照顧',
              unitPrice: 500,
              quantity: 4,
              dates: ['2026-08-01', '2026-08-02', '2026-08-03', '2026-08-04'],
              timesPerDay: 1
            },
            {
              category: 'BOOKING',
              serviceName: '進階陪伴',
              unitPrice: 800,
              quantity: 1,
              dates: ['2026-08-05'],
              timesPerDay: 1
            }
          ]
        })
      });
    });

    await page.route(`**/api/orders/${orderId}/confirm**`, async (route) => {
      orderStatus = 'PENDING_PAYMENT';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '訂單已確認' })
      });
    });

    await page.route(`**/api/orders/${orderId}/quote**`, async (route) => {
      orderStatus = 'PENDING_PAYMENT';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '報價已送出' })
      });
    });

    await page.route(`**/api/orders/${orderId}/reject**`, async (route) => {
      orderStatus = 'CANCELLED';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已拒絕此訂單' })
      });
    });

    // 動作完成後會導回 /sitter/orders，該頁會打這支清單 API
    await page.route('**/api/orders/sitter', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      });
    });

    await page.goto('/');
  });

  test('顯示真實訂單明細與加價/折扣即時計算總額', async ({ page }) => {
    await page.goto(`/sitter/eval/${orderId}`);

    await expect(page.getByTestId('sitter-order-eval-owner-name')).toContainText('陳先生');
    await expect(page.locator('text=基礎照顧')).toBeVisible();
    await expect(page.locator('text=進階陪伴')).toBeVisible();

    const totalText = page.getByTestId('sitter-order-eval-total');
    await expect(totalText).toHaveText('$ 2,400');

    await page.getByTestId('sitter-order-eval-input-add-fee').fill('100');
    await expect(totalText).toHaveText('$ 2,500');

    await page.getByTestId('sitter-order-eval-input-discount').fill('200');
    await expect(totalText).toHaveText('$ 2,300');
  });

  test('無調整金額時可原價直接接受', async ({ page }) => {
    await page.goto(`/sitter/eval/${orderId}`);

    await expect(page.getByTestId('sitter-order-eval-btn-accept-original')).toBeVisible();

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByTestId('sitter-order-eval-btn-accept-original').click();

    await expect(page).toHaveURL(/\/sitter\/orders$/);
    expect(orderStatus).toBe('PENDING_PAYMENT');
  });

  test('有加價/折扣時需填寫理由才能送出報價', async ({ page }) => {
    await page.goto(`/sitter/eval/${orderId}`);

    await page.getByTestId('sitter-order-eval-input-add-fee').fill('300');
    await expect(page.getByTestId('sitter-order-eval-btn-confirm')).toBeVisible();

    // 未填理由：擋下並顯示 alert
    page.once('dialog', (dialog) => {
      expect(dialog.message()).toContain('請填寫調整原因');
      dialog.accept();
    });
    await page.getByTestId('sitter-order-eval-btn-confirm').click();
    expect(orderStatus).toBe('PENDING');

    // 補上理由後成功送出
    await page.getByTestId('sitter-order-eval-input-reason').fill('距離較遠加收車馬費');
    await page.getByTestId('sitter-order-eval-btn-confirm').click();

    await expect(page).toHaveURL(/\/sitter\/orders$/);
    expect(orderStatus).toBe('PENDING_PAYMENT');
  });

  test('可拒絕接單並導回訂單列表', async ({ page }) => {
    await page.goto(`/sitter/eval/${orderId}`);

    await page.getByTestId('sitter-order-eval-btn-reject').click();
    await page.getByTestId('sitter-order-eval-input-reject-reason').fill('臨時有事無法接單');

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByTestId('sitter-order-eval-btn-submit-reject').click();

    await expect(page).toHaveURL(/\/sitter\/orders$/);
    expect(orderStatus).toBe('CANCELLED');
  });
});
