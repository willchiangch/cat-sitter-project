import { test, expect } from '@playwright/test';
import { step } from './support/evidence';
import {
  assertNoUnmockedApi,
  mockDemoSession,
  mockOwnerOrderDetail,
  type MockVisit
} from './support/mockSession';

/**
 * 訂單詳情依「行程狀態」控制操作入口。
 * 驗收 N10（.e2e-runs/20261024-2325）：CONFIRMED、行程還是「待執行」，飼主詳情頁就顯示「確認結案 (完成預約)」，
 * 按了只會被後端 CompletionService.manualComplete 擋下（「尚有未完成的行程」）。PRD-009 A.1：所有行程
 * 都結束才顯示。
 */
const ORDER_ID = 'a1023000-0000-0000-0000-000000000a10';

test.describe('訂單詳情依行程狀態控制操作入口', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  const completeCases: {
    label: string;
    status: string;
    visits: MockVisit[];
    canComplete: boolean;
  }[] = [
    {
      label: 'CONFIRMED、行程待執行：不顯示確認結案',
      status: 'CONFIRMED',
      visits: [{ id: 'v-pending', status: 'PENDING' }],
      canComplete: false
    },
    {
      label: 'IN_PROGRESS、一筆已完工一筆執行中：不顯示確認結案',
      status: 'IN_PROGRESS',
      visits: [
        { id: 'v-done', status: 'DONE', scheduledAt: '2026-12-01T00:00:00Z' },
        { id: 'v-running', status: 'IN_PROGRESS', scheduledAt: '2026-12-02T00:00:00Z' }
      ],
      canComplete: false
    },
    {
      label: '沒有任何行程：不顯示確認結案',
      status: 'CONFIRMED',
      visits: [],
      canComplete: false
    },
    {
      label: 'IN_PROGRESS、行程全部已完工或系統結案：顯示確認結案',
      status: 'IN_PROGRESS',
      visits: [
        { id: 'v-done', status: 'DONE', scheduledAt: '2026-12-01T00:00:00Z' },
        { id: 'v-closed', status: 'CLOSED_BY_SYSTEM', scheduledAt: '2026-12-02T00:00:00Z' }
      ],
      canComplete: true
    }
  ];

  for (const c of completeCases) {
    test(`N10 飼主詳情：${c.label}`, async ({ page }) => {
      await mockDemoSession(page, 'client');
      await mockOwnerOrderDetail(page, ORDER_ID, c.status, 600, c.visits);

      await step(c.label, page, async () => {
        await page.goto(`/owner/orders/${ORDER_ID}`);
        await expect(page.getByTestId('owner-order-visit-item')).toHaveCount(c.visits.length);
        // 申請變更、申報爭議不受行程狀態影響，仍要在
        await expect(page.getByTestId('dispute-order-btn')).toBeVisible();
        if (c.canComplete) {
          await expect(page.getByTestId('complete-order-btn')).toHaveText('確認結案 (完成預約)');
          await expect(page.getByTestId('complete-order-hint')).toHaveCount(0);
        } else {
          await expect(page.getByTestId('complete-order-btn')).toHaveCount(0);
          await expect(page.getByTestId('complete-order-hint')).toHaveText(
            '所有行程完工後即可確認結案'
          );
        }
      });
    });
  }
});
