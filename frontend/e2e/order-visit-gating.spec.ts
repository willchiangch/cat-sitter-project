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

  // fix-batch12 U5（驗收 N11 殘留）：過去 DONE 一律給「查看日誌」，已完工但保母還沒送出、或逾期被系統關閉的行程
  // 點進去是「尚無照護回報」空頁。改由後端 reportSubmitted 決定，未送出顯示狀態文字
  const OWNER_VISITS: MockVisit[] = [
    { id: 'v-pending', status: 'PENDING', scheduledAt: '2026-12-01T00:00:00Z' },
    { id: 'v-in_progress', status: 'IN_PROGRESS', scheduledAt: '2026-12-02T00:00:00Z' },
    { id: 'v-done-draft', status: 'DONE', scheduledAt: '2026-12-03T00:00:00Z' },
    {
      id: 'v-done',
      status: 'DONE',
      scheduledAt: '2026-12-04T00:00:00Z',
      reportSubmitted: true
    },
    { id: 'v-closed_by_system', status: 'CLOSED_BY_SYSTEM', scheduledAt: '2026-12-05T00:00:00Z' },
    { id: 'v-cancelled', status: 'CANCELLED', scheduledAt: '2026-12-06T00:00:00Z' }
  ];

  test('N11 飼主詳情：只有日誌已送出的行程顯示「查看日誌」，已完工未送出 / 逾期顯示狀態文字', async ({
    page
  }) => {
    await mockDemoSession(page, 'client');
    await mockOwnerOrderDetail(page, ORDER_ID, 'IN_PROGRESS', 600, OWNER_VISITS);

    await step('各行程入口：只有已送出日誌那筆有「查看日誌」', page, async () => {
      await page.goto(`/owner/orders/${ORDER_ID}`);
      await expect(page.getByTestId('owner-order-visit-item')).toHaveCount(OWNER_VISITS.length);
      const rows = await visitRows(page, 'owner-order-visit-item', 'owner-order-visit-link');
      expect(rows.map((r) => r.action)).toEqual([null, null, null, '查看日誌', null, null]);
      expect(rows.map((r) => r.text.replace(/^.*（|）$/g, ''))).toEqual([
        '待執行',
        '執行中',
        '已完工',
        '已完工',
        '系統結案',
        '已取消'
      ]);
      const pending = await page
        .getByTestId('owner-order-visit-item')
        .evaluateAll((items) =>
          items.map(
            (row) =>
              row.querySelector('[data-testid="owner-order-visit-report-pending"]')?.textContent ??
              null
          )
        );
      expect(pending).toEqual([null, null, '日誌尚未送出', null, '未送出日誌', null]);
    });

    await step('點已送出行程的「查看日誌」導到日誌檢視頁並看到內容', page, async () => {
      await page.route('**/api/visits/v-done/report', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              reportId: 'r-done',
              visitId: 'v-done',
              status: 'SUBMITTED',
              content: '貓咪精神很好，已完成餵食與清砂。',
              submittedAt: '2026-12-04T08:00:00Z',
              media: [],
              isEditable: false,
              version: 2,
              visitStatus: 'DONE',
              isPurged: false
            }
          })
        })
      );
      await page.getByTestId('owner-order-visit-link').click();
      await expect(page).toHaveURL(/\/visit-reports\/view\/v-done$/);
      await expect(page.getByTestId('client-report-text-card')).toContainText('貓咪精神很好');
      await expect(page.getByTestId('client-report-empty-state')).toHaveCount(0);
    });
  });

  // fix-batch13 K2（驗收 20261026-0007 截圖新問題 2）：結案、爭議後行程區塊整個消失（isServiceStage 只含
  // CONFIRMED / IN_PROGRESS，行程 API 根本不發），爭議中正是最需要回頭看日誌的時候。服務後狀態要看得到行程與
  // 已送出日誌，但不能出現申請變更 / 確認結案 / 申報爭議等動作按鈕
  const POST_SERVICE_VISITS: MockVisit[] = [
    { id: 'v-done', status: 'DONE', scheduledAt: '2026-12-01T00:00:00Z', reportSubmitted: true },
    { id: 'v-done-draft', status: 'DONE', scheduledAt: '2026-12-02T00:00:00Z' },
    { id: 'v-closed_by_system', status: 'CLOSED_BY_SYSTEM', scheduledAt: '2026-12-03T00:00:00Z' }
  ];
  const postServiceCases: { status: string; banner: string | null; extra?: string }[] = [
    { status: 'COMPLETED', banner: 'completed-status-banner' },
    { status: 'DISPUTED', banner: 'disputed-status-banner' },
    { status: 'MODIFYING', banner: null, extra: 'btn-go-modification-confirm' },
    { status: 'REFUND_VERIFY', banner: null, extra: 'btn-go-modification-confirm' }
  ];

  for (const c of postServiceCases) {
    test(`K2 飼主詳情 ${c.status}：行程區塊可見、可進入已送出日誌，沒有服務期間的動作按鈕`, async ({
      page
    }) => {
      await mockDemoSession(page, 'client');
      await mockOwnerOrderDetail(page, ORDER_ID, c.status, 600, POST_SERVICE_VISITS);
      await page.route('**/api/visits/v-done/report', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              reportId: 'r-done',
              visitId: 'v-done',
              status: 'SUBMITTED',
              content: `${c.status} 後仍可回看的照護日誌`,
              submittedAt: '2026-12-01T08:00:00Z',
              media: [],
              isEditable: false,
              version: 2,
              visitStatus: 'DONE',
              isPurged: false
            }
          })
        })
      );

      await step(
        `${c.status}：行程三筆、只有已送出那筆有「查看日誌」、沒有動作按鈕`,
        page,
        async () => {
          await page.goto(`/owner/orders/${ORDER_ID}`);
          await expect(page.getByTestId('order-status-badge')).toHaveAttribute(
            'data-status',
            c.status
          );
          await expect(page.getByTestId('owner-order-visit-item')).toHaveCount(3);
          const rows = await visitRows(page, 'owner-order-visit-item', 'owner-order-visit-link');
          expect(rows.map((r) => r.action)).toEqual(['查看日誌', null, null]);
          for (const id of [
            'btn-go-modification-wizard',
            'complete-order-btn',
            'complete-order-hint',
            'dispute-order-btn',
            'owner-cancel-booking-btn'
          ]) {
            await expect(page.getByTestId(id), `${c.status} 不應有 ${id}`).toHaveCount(0);
          }
          if (c.banner) await expect(page.getByTestId(c.banner)).toBeVisible();
          if (c.extra) await expect(page.getByTestId(c.extra)).toBeVisible();
        }
      );

      await step(`${c.status}：點「查看日誌」進入日誌檢視頁並看到內容`, page, async () => {
        await page.getByTestId('owner-order-visit-link').click();
        await expect(page).toHaveURL(/\/visit-reports\/view\/v-done$/);
        await expect(page.getByTestId('client-report-text-card')).toContainText(
          `${c.status} 後仍可回看的照護日誌`
        );
      });
    });
  }

  for (const status of ['PENDING', 'CANCELLED']) {
    test(`K2 飼主詳情 ${status}：訂單未成立，不顯示行程區塊`, async ({ page }) => {
      await mockDemoSession(page, 'client');
      await mockOwnerOrderDetail(page, ORDER_ID, status, 600, POST_SERVICE_VISITS);
      const visitRequests: string[] = [];
      page.on('request', (r) => {
        if (new URL(r.url()).pathname === `/api/orders/${ORDER_ID}/visits`)
          visitRequests.push(r.url());
      });

      await step(`${status}：詳情載入完成`, page, async () => {
        await page.goto(`/owner/orders/${ORDER_ID}`);
        await expect(page.getByTestId('order-status-badge')).toHaveAttribute('data-status', status);
      });

      // 上一步截圖前已等網路靜止：這時還沒發行程請求，就不是「還沒載入完」而是根本不抓
      await step(`${status}：沒有發行程請求、沒有行程清單`, page, async () => {
        expect(visitRequests).toEqual([]);
        await expect(page.getByTestId('owner-order-visit-item')).toHaveCount(0);
      });
    });
  }

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
