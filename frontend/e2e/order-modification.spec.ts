import { test, expect } from '@playwright/test';

test.describe('Order Modification Flow', () => {
  test.beforeEach(async ({ page }) => {
    // 攔截變更申請 API
    await page.route('**/api/orders/*/modify**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '變更請求已提交' })
      });
    });

    // 攔截保母報價 API
    await page.route('**/api/orders/*/quote**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '報價已送出' })
      });
    });

    // 攔截上傳憑證 API
    await page.route('**/api/orders/*/modification/refund-proof**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '退款憑證已成功上傳' })
      });
    });

    // 攔截飼主確認變更 API
    await page.route('**/api/orders/*/modification/confirm**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '變更已生效' })
      });
    });

    // 攔截確認退款 API
    await page.route('**/api/orders/*/modification/refund-confirm**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已確認收到退款' })
      });
    });

    await page.goto('/');
  });

  test('should propose modification and enforce date ranges', async ({ page }) => {
    await page.getByRole('button', { name: '直接進入變更精靈 (飼主/保母)' }).click();

    // 1. 測試超出範圍的日期防呆 (PLAN_NOT_IN_RANGE)
    await page.getByTestId('modify-dates-input').fill('2026-06-01');
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('PLAN_NOT_IN_RANGE');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();

    // 2. 測試正確區間的日期
    await page.getByTestId('modify-dates-input').fill('2026-05-26, 2026-05-27');
    page.once('dialog', async dialog => {
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
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('變更申請已成功提交');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();
    await expect(page.getByTestId('modification-success-banner')).toBeVisible();
  });

  test('should fine-tune quote and upload refund proof from sitter side', async ({ page }) => {
    await page.getByRole('button', { name: '切換為保母' }).click();
    await page.getByRole('button', { name: '直接進入變更報價 (保母端)' }).click();

    // 輸入報價與密碼認證
    await page.getByTestId('quote-amount-input').fill('1800');
    await page.getByTestId('quote-password-input').fill('password');
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('報價微調已成功送出');
      await dialog.accept();
    });
    await page.getByTestId('quote-submit-btn').click();
    await expect(page.getByTestId('quote-success-banner')).toBeVisible();

    // 上傳退款憑證
    await page.getByTestId('refund-proof-input').fill('https://receipt.jpg');
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('退款憑證已成功上傳');
      await dialog.accept();
    });
    await page.getByTestId('refund-proof-submit-btn').click();
    await expect(page.getByTestId('refund-proof-success-banner')).toBeVisible();
  });

  test('should confirm modification with zero-trust checks from owner side', async ({ page }) => {
    await page.getByRole('button', { name: '切換為飼主' }).click();
    await page.getByRole('button', { name: '直接進入變更確認 (飼主端)' }).click();

    // 1. 測試金額不一致的零信任對帳防線
    await page.getByTestId('agreed-diff-input').fill('-500');
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('零信任對帳警告');
      await dialog.accept();
    });
    await page.getByTestId('confirm-submit-btn').click();

    // 2. 金額相符時確認變更
    await page.getByTestId('agreed-diff-input').fill('-600');
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('變更確認成功');
      await dialog.accept();
    });
    await page.getByTestId('confirm-submit-btn').click();
    await expect(page.getByTestId('confirm-success-banner')).toBeVisible();

    // 3. 確認收到線下退款
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('已確認收到退款');
      await dialog.accept();
    });
    await page.getByTestId('refund-confirm-btn').click();
    await expect(page.getByTestId('refund-confirm-success-banner')).toBeVisible();
  });
});
