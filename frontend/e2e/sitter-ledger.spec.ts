import { test, expect } from '@playwright/test';

// 月份不寫死：以測試執行當下的年月作為「本月」，避免日期推移後測試失效
const now = new Date();
const currentYearMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
const prevDate = new Date(now.getFullYear(), now.getMonth() - 1, 1);
const prevYearMonth = `${prevDate.getFullYear()}-${String(prevDate.getMonth() + 1).padStart(2, '0')}`;

test.describe('PRD-009 保母帳務總覽', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    await page.route('**/api/orders/sitter/ledger**', async (route) => {
      const url = new URL(route.request().url());
      const month = url.searchParams.get('month') || currentYearMonth;
      const isCurrentMonth = month === currentYearMonth;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          yearMonth: month,
          totalRevenue: isCurrentMonth ? 3800 : 0,
          entries: isCurrentMonth
            ? [
                {
                  orderId: 'a1023000-0000-0000-0000-000000000000',
                  ownerName: '陳先生',
                  totalAmount: 2400,
                  paidAt: `${month}-01T10:00:00Z`,
                  completedAt: `${month}-10T10:00:00Z`,
                  payoutAt: `${month}-13T10:00:00Z`
                },
                {
                  orderId: 'a1023000-0000-0000-0000-000000000001',
                  ownerName: '林小姐',
                  totalAmount: 1400,
                  paidAt: `${month}-02T10:00:00Z`,
                  completedAt: `${month}-15T10:00:00Z`,
                  payoutAt: `${month}-18T10:00:00Z`
                }
              ]
            : []
        })
      });
    });

    await page.goto('/sitter/ledger');
  });

  test('顯示本月總收入與明細列表', async ({ page }) => {
    await expect(page.getByTestId('ledger-total-revenue')).toContainText('3,800');
    await expect(page.getByTestId('ledger-entry-list')).toBeVisible();
    await expect(page.locator('text=陳先生')).toBeVisible();
    await expect(page.locator('text=林小姐')).toBeVisible();
  });

  test('切換月份後重新查詢並顯示空狀態', async ({ page }) => {
    await page.getByTestId('ledger-prev-month-btn').click();
    await expect(page.getByTestId('ledger-current-month')).toHaveText(prevYearMonth);
    await expect(page.getByTestId('ledger-total-revenue')).toContainText('0');
    await expect(page.locator('text=本月尚無已結案訂單')).toBeVisible();
  });
});
