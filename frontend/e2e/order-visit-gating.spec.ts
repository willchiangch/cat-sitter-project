import { test, expect, type Page } from '@playwright/test';
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
 * 驗收 N11：行程清單不分狀態一律顯示「查看日誌」，飼主點「待執行」行程只會看到空日誌。改依行程狀態給文案或不給入口。
 */
const ORDER_ID = 'a1023000-0000-0000-0000-000000000a10';

/** 每種行程狀態各一筆，日期遞增讓清單順序固定 */
const ALL_VISITS: MockVisit[] = [
  'PENDING',
  'IN_PROGRESS',
  'DONE',
  'CLOSED_BY_SYSTEM',
  'CANCELLED'
].map((status, i) => ({
  id: `v-${status.toLowerCase()}`,
  status,
  scheduledAt: `2026-12-0${i + 1}T00:00:00Z`
}));

/** 依清單順序（= ALL_VISITS 順序）回傳每列的狀態文字與入口按鈕文字（沒有入口為 null） */
async function visitRows(page: Page, itemTestId: string, linkTestId: string) {
  return page.getByTestId(itemTestId).evaluateAll(
    (rows, link) =>
      rows.map((row) => ({
        text: row.querySelector('span')?.textContent ?? '',
        action: row.querySelector(`[data-testid="${link}"]`)?.textContent ?? null
      })),
    linkTestId
  );
}

test.describe('訂單詳情依行程狀態控制操作入口', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  const completeCases: {
    label: string;
    status: string;
    visits: MockVisit[];
    canComplete: boolean;
    hint?: string;
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
      label: '沒有任何行程：不顯示確認結案，提示聯繫保母或平台',
      status: 'CONFIRMED',
      visits: [],
      canComplete: false,
      // fix-batch12 U4：零行程永遠不會「所有行程完工」，過去提示會讓飼主卡住（PRD-009 B.3 由管理員人工處理）
      hint: '此訂單沒有任何行程，無法確認結案，請聯繫保母或平台管理員協助處理'
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
            c.hint ?? '所有行程完工後即可確認結案'
          );
        }
      });
    });
  }

  test('N11 飼主詳情：只有已完工的行程顯示「查看日誌」', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockOwnerOrderDetail(page, ORDER_ID, 'IN_PROGRESS', 600, ALL_VISITS);

    await step('各狀態行程的入口：待執行 / 執行中 / 系統結案 / 已取消不顯示', page, async () => {
      await page.goto(`/owner/orders/${ORDER_ID}`);
      await expect(page.getByTestId('owner-order-visit-item')).toHaveCount(ALL_VISITS.length);
      const rows = await visitRows(page, 'owner-order-visit-item', 'owner-order-visit-link');
      expect(rows.map((r) => r.action)).toEqual([null, null, '查看日誌', null, null]);
      expect(rows.map((r) => r.text.replace(/^.*（|）$/g, ''))).toEqual([
        '待執行',
        '執行中',
        '已完工',
        '系統結案',
        '已取消'
      ]);
    });

    await step('點已完工行程的「查看日誌」導到日誌檢視頁', page, async () => {
      await page.route('**/api/visits/v-done/report', (route) =>
        route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'MSG_DATA_F11', message: '查無服務日誌' })
        })
      );
      await page.getByTestId('owner-order-visit-link').click();
      await expect(page).toHaveURL(/\/visit-reports\/view\/v-done$/);
      await expect(page.getByTestId('client-report-empty-state')).toBeVisible();
    });
  });

  test('N11 保母訂單卡：依行程狀態顯示打卡 / 繼續回報 / 查看送出，系統結案與已取消不顯示', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    await page.route('**/api/orders/sitter', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: ORDER_ID,
            ownerId: 'o-1',
            ownerName: '陳先生',
            sitterId: 's-1',
            status: 'IN_PROGRESS',
            totalAmount: 3000,
            amountDue: 3000,
            hasPaymentProof: true,
            paymentProofLastFive: '12345',
            scheduledDatesLabel: '2026-12-01 ~ 2026-12-05 (共 5 天)'
          }
        ])
      })
    );
    await page.route(`**/api/orders/${ORDER_ID}/visits`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ALL_VISITS)
      })
    );

    await step('進行中分頁的行程清單入口文案', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('sitter-order-visit-item')).toHaveCount(ALL_VISITS.length);
      const rows = await visitRows(page, 'sitter-order-visit-item', 'sitter-order-visit-link');
      expect(rows.map((r) => r.action)).toEqual([
        '打卡 / 回報',
        '繼續回報',
        '查看 / 送出日誌',
        null,
        null
      ]);
    });
  });
});
