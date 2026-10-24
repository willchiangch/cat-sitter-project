import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession, mockOwnerOrderDetail } from './support/mockSession';
import {
  contrastOf,
  expectInside,
  expectNoHorizontalOverflow,
  gradientContrastOf
} from './support/layout';

/**
 * Backlog 第 6 批 G5（驗收截圖 N1、N3~N7）：390px AppShell 內容溢出、低對比按鈕、管理員主題沒有按鈕底色。
 * 溢出以「<main> 內容區沒有被撐寬」+ 指定元素落在所屬卡片內判定；對比度以實際渲染色計算（WCAG AA 4.5:1）。
 * N2（KYC 核准後按鈕停用樣式）在 admin-kyc-detail.spec.ts，N8（協商結清空狀態）在 order-modification.spec.ts。
 */
const LONG_ID = '1792818105095';
const SITTER_ID = '3d498178-14c0-4376-b81e-7fb02e615dda';
const ORDER_ID = 'a1023000-0000-0000-0000-0000000000f5';

const json = (body: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify(body)
});

async function mockKycPending(page: Page, content: object[]) {
  await page.route('**/api/admin/kyc/pending**', (route) =>
    route.fulfill(
      json({
        data: { content, page: 0, size: 10, totalElements: content.length, totalPages: 1 }
      })
    )
  );
}

test.describe('G5 版面溢出與對比度', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('N1：/admin/kyc 單欄排版，停權工具卡在殼內，空清單文字不再一字一行', async ({ page }) => {
    await mockDemoSession(page, 'admin');
    await mockKycPending(page, []);

    await step('空清單：兩張卡都在殼內、空狀態文字為單行寬度', page, async () => {
      await page.goto('/admin/kyc');
      const empty = page.getByTestId('admin-kyc-empty');
      await expect(empty).toHaveText('目前無待審核的實名認證申請。');
      await expectNoHorizontalOverflow(page);
      await expectInside(
        page.getByTestId('admin-kyc-suspend-card'),
        page.locator('main'),
        '停權工具卡'
      );
      // 過去欄寬被擠到只剩一兩個字：文字高度 > 寬度。現在至少要有 150px 寬
      const box = (await empty.boundingBox())!;
      expect(box.width, '空狀態文字寬度').toBeGreaterThan(150);
    });
  });

  test('N1：/admin/kyc 長 Email 的待審列落在卡片內，「進入審查」可見', async ({ page }) => {
    await mockDemoSession(page, 'admin');
    await mockKycPending(page, [
      {
        recordId: 'r-long',
        sitterId: 's-long',
        fullName: `journey-a-sitter-${LONG_ID}`,
        email: `journey-a-sitter-${LONG_ID}-k9edxl@e2e-journey.test`,
        submittedAt: '2026-10-24T00:00:00Z'
      }
    ]);

    await step('待審列：Email 換行、審查按鈕在卡片內', page, async () => {
      await page.goto('/admin/kyc');
      const row = page.getByTestId('admin-kyc-row');
      await expect(row).toContainText(`journey-a-sitter-${LONG_ID}-k9edxl@e2e-journey.test`);
      await expectNoHorizontalOverflow(page);
      const card = page.getByTestId('admin-kyc-pending-card');
      await expectInside(row, card, '待審列');
      await expectInside(row.getByTestId('admin-kyc-review-btn'), card, '進入審查按鈕');
    });
  });

  test('N3：預約頁保母卡片長顯示名稱換行，愛心留在卡片內', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await page.route(
      (url) => url.pathname === `/api/sitter/profile/${SITTER_ID}`,
      (route) =>
        route.fulfill(
          json({
            gated: false,
            sitterId: SITTER_ID,
            displayName: `Journey保母A公開顯示名稱-${LONG_ID}`,
            avatarUrl: '',
            bio: '版面測試',
            tags: [],
            serviceAreas: [],
            isOpen: true
          })
        )
    );
    await page.route(
      (url) => url.pathname === `/api/sitters/${SITTER_ID}/plans`,
      (route) =>
        route.fulfill(
          json({
            data: [
              {
                id: 'p-n3',
                sitterId: SITTER_ID,
                name: '基礎照顧',
                price: 600,
                durationMinutes: 60,
                dailyCapacity: 2,
                applicablePetTypes: ['CAT'],
                defaultTasks: [],
                isActive: true
              }
            ]
          })
        )
    );
    await page.route(
      (url) => url.pathname === `/api/sitters/${SITTER_ID}/questions`,
      (route) => route.fulfill(json([]))
    );
    await page.route('**/api/owner/favorites', (route) => route.fulfill(json([])));
    await page.route(
      (url) => url.pathname === '/api/pets',
      (route) => route.fulfill(json({ data: [] }))
    );

    await step('保母卡片：名稱、愛心都在卡片內', page, async () => {
      await page.goto(`/booking/${SITTER_ID}`);
      await expect(page.getByTestId('client-booking-sitter-name')).toHaveText(
        `Journey保母A公開顯示名稱-${LONG_ID}`
      );
      await expectNoHorizontalOverflow(page);
      const card = page.getByTestId('client-booking-sitter-card');
      await expectInside(page.getByTestId('client-booking-sitter-name'), card, '保母名稱');
      await expectInside(page.getByTestId('client-booking-favorite-toggle'), card, '愛心');
    });
  });

  test('N4：照護記事本管理頁分區卡片與「儲存備忘錄」不超出殼', async ({ page }) => {
    await mockDemoSession(page, 'sitter');
    await page.route('**/api/care-notes/templates', (route) => route.fulfill(json({ data: [] })));
    await page.route(
      (url) => url.pathname === '/api/care-notes/s-1/o-1',
      (route) =>
        route.fulfill(
          json({
            data: {
              careNoteId: 'c-1',
              sitterId: 's-1',
              ownerId: 'o-1',
              sections: {
                SERVICE: [
                  {
                    id: 'i-1',
                    sectionType: 'SERVICE',
                    title: `Journey B 記事本測試內容-${LONG_ID}`,
                    content: `Journey B 記事本測試內容-${LONG_ID}`,
                    sortOrder: 0
                  }
                ]
              }
            }
          })
        )
    );
    await page.route(
      (url) => url.pathname === '/api/care-media/s-1/o-1',
      (route) => route.fulfill(json({ data: [] }))
    );

    await step('記事本管理頁：沒有元素超出 <main> 內容區', page, async () => {
      await page.goto('/care-notes/manage/s-1/o-1');
      await expect(page.getByTestId('sitter-carenote-save-btn')).toHaveText('儲存備忘錄');
      await expect(page.getByTestId('sitter-carenote-item-title')).toHaveValue(
        `Journey B 記事本測試內容-${LONG_ID}`
      );
      await expectNoHorizontalOverflow(page);
      await expectInside(
        page.getByTestId('sitter-carenote-save-btn'),
        page.locator('main'),
        '儲存備忘錄'
      );
    });
  });

  test('N5：日誌回報頁「上傳」按鈕留在媒體卡片內，狀態徽章不被擠成直排', async ({ page }) => {
    await mockDemoSession(page, 'sitter');
    await page.route('**/api/visits/v-n5/report', (route) =>
      route.fulfill(
        json({
          data: {
            reportId: 'r-n5',
            visitId: 'v-n5',
            status: 'DRAFT',
            content: '',
            submittedAt: null,
            media: [],
            isEditable: true,
            version: 1,
            visitStatus: 'IN_PROGRESS'
          }
        })
      )
    );

    await step('執行中的日誌頁：上傳按鈕與徽章版面', page, async () => {
      await page.goto('/visit-reports/manage/v-n5');
      const upload = page.getByTestId('sitter-report-media-upload-btn');
      await expect(upload).toHaveText('上傳');
      await expectNoHorizontalOverflow(page);
      await expectInside(upload, page.getByTestId('sitter-report-media-card'), '上傳按鈕');
      // 徽章「執行中」要單行：高度不超過一行（過去被擠成一字一行，高度約三倍）
      const badge = page.getByTestId('sitter-report-status-badge');
      await expect(badge).toHaveText('執行中');
      const box = (await badge.boundingBox())!;
      expect(box.width, '徽章寬應大於高').toBeGreaterThan(box.height);
    });
  });

  test('N6：飼主訂單詳情「申請訂單變更」「申報爭議」實色底、文字對比度 ≥ 4.5:1', async ({
    page
  }) => {
    await mockDemoSession(page, 'client');
    await mockOwnerOrderDetail(page, ORDER_ID, 'CONFIRMED');

    await step('CONFIRMED 訂單詳情的次要按鈕', page, async () => {
      await page.goto(`/owner/orders/${ORDER_ID}`);
      await expect(page.getByTestId('order-status-badge')).toHaveText('已確認');
      for (const [testId, label] of [
        ['btn-go-modification-wizard', '申請訂單變更 (改期 / 取消)'],
        ['dispute-order-btn', '申報爭議']
      ]) {
        const btn = page.getByTestId(testId);
        await expect(btn).toHaveText(label);
        const c = await contrastOf(btn);
        // 有漸層時 background-color 不是實際看到的底色，算出的對比度沒有意義
        expect(c.backgroundImage, `${label} 不應疊漸層`).toBe('none');
        expect(
          c.ratio,
          `${label} 對比度（${c.color} on ${c.backgroundColor}）`
        ).toBeGreaterThanOrEqual(4.5);
      }
    });
  });

  test('N7：管理員身分 /demo 的切換鈕與功能入口有底色，文字對漸層兩端對比度 ≥ 4.5:1', async ({
    page
  }) => {
    await mockDemoSession(page, 'admin');

    await step('管理員 /demo 的按鈕樣式', page, async () => {
      await page.goto('/demo');
      await expect(page.getByText('當前角色:')).toContainText('系統管理員');
      for (const name of ['切換為管理員', '進入 KYC 審核清單 (管理端)']) {
        const btn = page.getByRole('button', { name });
        await expect(btn).toBeVisible();
        const g = await gradientContrastOf(btn);
        expect(
          g.stops.length,
          `${name} 應有漸層底色（過去 admin 主題沒有 --color-primary-gradient）`
        ).toBe(2);
        expect(
          g.minRatio,
          `${name} 文字 ${g.color} 對 ${g.stops.join(' / ')}`
        ).toBeGreaterThanOrEqual(4.5);
      }
    });
  });
});
