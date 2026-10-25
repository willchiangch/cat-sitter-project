import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession } from './support/mockSession';

/**
 * fix-batch12 U6（驗收 B2-04、第 9 批另外發現 #4）：保母議價時填的「調整原因」，保母端表單寫著「飼主會看到」，
 * 但飼主訂單詳情過去只顯示調整後總額，看不到加價 / 折扣多少、為什麼。
 */
const orderId = 'a1023000-0000-0000-0000-0000000000a6';

async function mockOrder(page: Page, adjustmentAmount: number, adjustmentReason: string | null) {
  await page.route(`**/api/orders/${orderId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: orderId,
        ownerId: 'o-1',
        sitterId: 's-1',
        status: 'PENDING_PAYMENT',
        totalAmount: 600 + adjustmentAmount,
        amountDue: 600 + adjustmentAmount,
        adjustmentAmount,
        adjustmentReason,
        disclaimerAgreed: false,
        hasPaymentProof: false,
        paymentProofLastFive: null,
        items: [],
        sitterPaymentInfo: null,
        cancellableByOwner: true
      })
    })
  );
}

test.describe('U6 飼主訂單詳情顯示保母報價調整', () => {
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('加價：顯示「加價 $ 200」與調整原因', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockOrder(page, 200, '距離較遠加收車馬費');

    await step('待付款詳情：總額 $800、加價 $200、原因', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('order-total-amount')).toHaveText('$ 800');
      await expect(page.getByTestId('order-adjustment-amount')).toHaveText('加價 $ 200');
      await expect(page.getByTestId('order-adjustment-reason')).toHaveText(
        '調整原因：距離較遠加收車馬費'
      );
    });
  });

  test('折扣：顯示「折扣 $ 100」與調整原因', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockOrder(page, -100, '老客戶折扣');

    await step('待付款詳情：總額 $500、折扣 $100、原因', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('order-total-amount')).toHaveText('$ 500');
      await expect(page.getByTestId('order-adjustment-amount')).toHaveText('折扣 $ 100');
      await expect(page.getByTestId('order-adjustment-reason')).toHaveText('調整原因：老客戶折扣');
    });
  });

  test('原價接單：沒有調整區塊', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockOrder(page, 0, null);

    await step('待付款詳情：總額 $600，沒有調整區塊', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('order-total-amount')).toHaveText('$ 600');
      await expect(page.getByTestId('order-adjustment-summary')).toHaveCount(0);
    });
  });
});
