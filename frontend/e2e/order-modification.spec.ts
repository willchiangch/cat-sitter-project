import { test, expect } from '@playwright/test';

const orderId = 'a1023000-0000-0000-0000-000000000000';

test.describe('Order Modification Flow', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    // 訂單詳情 GET — 變更精靈需要先讀取原始項目內容，才能在變更時保留正確欄位
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
          status: 'CONFIRMED',
          totalAmount: 2400,
          version: 1,
          adjustmentAmount: 0,
          disclaimerAgreed: true,
          items: [
            {
              category: 'BOOKING',
              serviceName: '基礎照顧',
              unitPrice: 500,
              quantity: 4,
              planId: '3d498178-14c0-4376-b81e-7fb02e615dda',
              dates: ['2026-05-25', '2026-05-26', '2026-05-27', '2026-05-28'],
              timesPerDay: 1
            }
          ]
        })
      });
    });

    // 進行中的變更請求 GET — 保母報價頁與飼主確認頁都需要真實資料，不再寫死測試 UUID
    await page.route(`**/api/orders/${orderId}/modification`, async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'b2034111-0000-0000-0000-000000000000',
          orderId,
          status: 'QUOTED',
          requestedBy: 'OWNER',
          diffAmount: -600,
          newTotalAmount: 1800,
          currentOrderTotalAmount: 2400,
          orderVersion: 1,
          dates: ['2026-05-26', '2026-05-27'],
          refundProofUrl: null
        })
      });
    });

    // 攔截變更申請 API
    await page.route(`**/api/orders/${orderId}/modify**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '變更請求已提交' })
      });
    });

    // 攔截保母報價 API
    await page.route(`**/api/orders/${orderId}/modification/quote**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '報價已送出' })
      });
    });

    // 攔截保母拒絕變更 API
    await page.route(`**/api/orders/${orderId}/modification/reject**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已拒絕此變更請求' })
      });
    });

    // 攔截上傳憑證 API
    await page.route(`**/api/orders/${orderId}/modification/refund-proof**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '退款憑證已成功上傳' })
      });
    });

    // 攔截飼主確認變更 API
    await page.route(`**/api/orders/${orderId}/modification/confirm**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '變更已生效' })
      });
    });

    // 攔截確認退款 API
    await page.route(`**/api/orders/${orderId}/modification/refund-confirm**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已確認收到退款' })
      });
    });

    // 單一導覽：後續都靠 React Router client-side 導覽，避免像 order-eval.spec.ts
    // 曾遇到的「同帳號連續兩次真實登入」併發衝突
    await page.goto('/demo');
  });

  test('should propose modification and enforce date ranges', async ({ page }) => {
    await page.getByRole('button', { name: '直接進入變更精靈 (飼主/保母)' }).click();

    // 1. 測試超出範圍的日期防呆 (PLAN_NOT_IN_RANGE)
    await page.getByTestId('modify-dates-input').fill('2026-06-01');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('PLAN_NOT_IN_RANGE');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();

    // 2. 測試正確區間的日期
    await page.getByTestId('modify-dates-input').fill('2026-05-26, 2026-05-27');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('變更申請已成功提交');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();
    await expect(page.getByTestId('modification-success-banner')).toBeVisible();
  });

  test('should support cancellation by submitting empty dates', async ({ page }) => {
    await page.getByRole('button', { name: '直接進入變更精靈 (飼主/保母)' }).click();

    // 勾選取消預約
    await page.getByTestId('cancel-checkbox').check();
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('變更申請已成功提交');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();
    await expect(page.getByTestId('modification-success-banner')).toBeVisible();
  });

  test('should review quote and upload refund proof from sitter side', async ({ page }) => {
    await page.getByRole('button', { name: '切換為保母' }).click();
    await page.getByRole('button', { name: '直接進入變更報價 (保母端)' }).click();

    // 頁面已從真實 API 帶入保母的報價金額
    await expect(page.getByTestId('quote-amount-input')).toHaveValue('1800');

    await page.getByTestId('quote-amount-input').fill('1800');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('報價已成功送出');
      await dialog.accept();
    });
    await page.getByTestId('quote-submit-btn').click();
    await expect(page.getByTestId('quote-success-banner')).toBeVisible();

    // 上傳退款憑證
    await page.getByTestId('refund-proof-input').fill('https://receipt.jpg');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('退款憑證已成功上傳');
      await dialog.accept();
    });
    await page.getByTestId('refund-proof-submit-btn').click();
    await expect(page.getByTestId('refund-proof-success-banner')).toBeVisible();
  });

  test('should allow sitter to reject the modification request', async ({ page }) => {
    await page.getByRole('button', { name: '切換為保母' }).click();
    await page.getByRole('button', { name: '直接進入變更報價 (保母端)' }).click();

    await expect(page.getByTestId('reject-mod-btn')).toBeVisible();

    // 一次點擊會依序觸發 window.confirm() 與成功後的 alert()，用持續監聽收集訊息順序
    const dialogMessages: string[] = [];
    page.on('dialog', async (dialog) => {
      dialogMessages.push(dialog.message());
      await dialog.accept();
    });

    await page.getByTestId('reject-mod-btn').click();
    await expect(page.getByTestId('reject-success-banner')).toBeVisible();

    expect(dialogMessages[0]).toContain('確定要拒絕此變更請求');
    expect(dialogMessages[1]).toContain('已拒絕此變更請求');
  });

  test('should confirm modification with zero-trust checks from owner side', async ({ page }) => {
    await page.getByRole('button', { name: '切換為飼主' }).click();
    await page.getByRole('button', { name: '直接進入變更確認 (飼主端)' }).click();

    // 頁面已從真實 API 帶入保母報價差額
    await expect(page.getByTestId('mod-confirm-diff-amount')).toContainText('-600');

    // 1. 測試金額不一致的零信任對帳防線
    await page.getByTestId('agreed-diff-input').fill('-500');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('零信任對帳警告');
      await dialog.accept();
    });
    await page.getByTestId('confirm-submit-btn').click();

    // 2. 金額相符時確認變更
    await page.getByTestId('agreed-diff-input').fill('-600');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('變更確認成功');
      await dialog.accept();
    });
    await page.getByTestId('confirm-submit-btn').click();
    await expect(page.getByTestId('confirm-success-banner')).toBeVisible();

    // 3. 確認收到線下退款
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('已確認收到退款');
      await dialog.accept();
    });
    await page.getByTestId('refund-confirm-btn').click();
    await expect(page.getByTestId('refund-confirm-success-banner')).toBeVisible();
  });
});
