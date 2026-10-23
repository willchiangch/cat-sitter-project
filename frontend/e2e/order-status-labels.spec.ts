import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';
import { mockDemoSession } from './support/mockSession';

/**
 * Backlog 第 3 批 U5（驗收 P6）：飼主訂單詳情徽章直接顯示英文 IN_PROGRESS。
 * 所有狀態文字改由 src/components/ui/statusLabels.ts 集中對照；這裡逐一驗訂單徽章與行程清單。
 */
const orderId = 'c1000000-0000-0000-0000-0000000005a7';

const EXPECTED: Record<string, string> = {
  PENDING: '待評估',
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  CONFIRMED: '已確認',
  IN_PROGRESS: '服務進行中',
  MODIFYING: '變更協商中',
  REFUND_VERIFY: '待確認退款',
  COMPLETED: '已結案',
  CANCELLED: '已取消',
  DISPUTED: '爭議處理中'
};

async function mockOrder(page: Page, status: () => string) {
  await page.route(`**/api/orders/${orderId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: orderId,
        ownerId: 'o-1',
        sitterId: 's-1',
        status: status(),
        totalAmount: 1200,
        amountDue: 1200,
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
      body: JSON.stringify([
        { id: 'v-1', status: 'DONE', scheduledAt: '2026-10-24T01:00:00Z' },
        { id: 'v-2', status: 'IN_PROGRESS', scheduledAt: '2026-10-25T01:00:00Z' },
        { id: 'v-3', status: 'CONFIRMED', scheduledAt: '2026-10-26T01:00:00Z' }
      ])
    })
  );
}

test.describe('狀態徽章一律顯示中文 (U5 / 驗收 P6)', () => {
  test('飼主訂單詳情：IN_PROGRESS 徽章顯示「服務進行中」，行程清單狀態也是中文', async ({
    page
  }) => {
    await mockDemoSession(page, 'client');
    await mockOrder(page, () => 'IN_PROGRESS');

    await step('開飼主訂單詳情 (IN_PROGRESS)', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      const badge = page.getByTestId('order-status-badge');
      await expect(badge).toHaveText('服務進行中');
      await expect(badge).not.toContainText('IN_PROGRESS');
      const visits = page.locator('main');
      await expect(visits).toContainText('（已完工）');
      await expect(visits).toContainText('（執行中）');
      await expect(visits).toContainText('（待執行）');
      await expect(visits).not.toContainText(/（[A-Z_]+）/);
    });
  });

  test('所有訂單狀態碼都有中文徽章，不會漏出英文狀態碼', async ({ page }) => {
    await mockDemoSession(page, 'client');
    let current = 'PENDING';
    await mockOrder(page, () => current);

    for (const [code, label] of Object.entries(EXPECTED)) {
      await step(`狀態 ${code} → ${label}`, page, async () => {
        current = code;
        await page.goto(`/owner/orders/${orderId}`);
        const badge = page.getByTestId('order-status-badge');
        await expect(badge).toHaveAttribute('data-status', code);
        await expect(badge).toHaveText(label);
      });
    }
  });
});
