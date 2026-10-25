import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';
import {
  assertNoUnmockedApi,
  mockDemoSession,
  mockOwnerOrderDetail,
  mockOwnerOrderList,
  serveFixtureImage
} from './support/mockSession';
import {
  contrastOf,
  effectiveGradientContrastOf,
  expectInside,
  expectNoHorizontalOverflow,
  expectNoOverlap,
  expectStandardPageInset,
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

/** 預約頁（/booking/{id}）mock：長顯示名稱、沒有頭像、一個貓咪方案、沒有寵物 */
async function mockLongNameBookingPage(page: Page) {
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
    await mockLongNameBookingPage(page);

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

  test('N14：預約頁保母卡長名稱不被頭像擠成多行、日期欄不被照護對象區塊擠壞', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockLongNameBookingPage(page);
    const placeholderRequests: string[] = [];
    page.on('request', (r) => {
      if (r.url().includes('via.placeholder.com')) placeholderRequests.push(r.url());
    });

    await step('保母卡：頭像與愛心同列，名稱佔滿卡片寬度、最多兩行', page, async () => {
      await page.goto(`/booking/${SITTER_ID}`);
      const name = page.getByTestId('client-booking-sitter-name');
      await expect(name).toHaveText(`Journey保母A公開顯示名稱-${LONG_ID}`);
      // 沒有頭像：名稱首字佔位，不再打外部 placeholder 服務
      await expect(page.getByTestId('client-booking-sitter-avatar')).toHaveText('J');
      expect(placeholderRequests).toEqual([]);
      const card = page.getByTestId('client-booking-sitter-card');
      await expectInside(name, card, '保母名稱');
      await expectInside(page.getByTestId('client-booking-favorite-toggle'), card, '愛心');
      const { height, lineHeight } = await name.evaluate((el) => ({
        height: el.getBoundingClientRect().height,
        lineHeight: parseFloat(getComputedStyle(el).lineHeight)
      }));
      // 過去名稱欄只剩約 100px，同一個名稱是 6 行
      expect(Math.round(height / lineHeight), '名稱行數').toBeLessThanOrEqual(2);
    });

    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const dateStr = tomorrow.toISOString().split('T')[0];
    await step('排程配置：日期欄單行顯示，照護對象區塊在下一列', page, async () => {
      await page.getByTestId('client-booking-plan-card-0').click();
      await expect(page.getByRole('heading', { name: '排程配置' })).toBeVisible();
      await page.getByTestId(`client-booking-date-0-0-${dateStr}`).click();
      await page.getByTestId('client-booking-btn-confirm-date-0-0').click();
      const dates = page.getByTestId('client-booking-schedule-dates-0-0');
      const pets = page.getByTestId('client-booking-pet-section-0-0');
      await expect(dates).toContainText(`${tomorrow.getFullYear()}年`);
      await expect(pets).toContainText('照護對象 (毛孩)');
      await expectNoHorizontalOverflow(page);
      await expectNoOverlap(dates, pets, '日期欄與照護對象');
      const datesBox = (await dates.boundingBox())!;
      const petsBox = (await pets.boundingBox())!;
      expect(petsBox.y, '照護對象在日期下方').toBeGreaterThanOrEqual(datesBox.y + datesBox.height);
      // 過去日期欄被壓到約 60px，「2026年」拆成兩行
      const year = dates.getByText(`${tomorrow.getFullYear()}年`);
      const yearBox = (await year.boundingBox())!;
      const fontSize = await year.evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
      expect(yearBox.height, '年份單行').toBeLessThan(fontSize * 1.8);
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
      // 驗收截圖附帶觀察（.e2e-runs/20261024-1939）：紅色「移除」疊在標題輸入框右上角，
      // expectNoHorizontalOverflow 抓不到卡片內的重疊
      await expect(page.getByTestId('sitter-carenote-item-remove')).toHaveText('移除');
      await expectNoOverlap(
        page.getByTestId('sitter-carenote-item-remove'),
        page.getByTestId('sitter-carenote-item-title'),
        '「移除」與標題輸入框'
      );
      // 驗收 N17：過去頁面容器 padding '1rem 0'，內容比其他頁左右各寬 24px
      await expectStandardPageInset(page, '記事本管理頁');
    });
  });

  test('N17：標準頁面（方案設定）的內容左右內縮是 48px，作為 KYC 詳情 / 記事本管理頁的對照', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    await page.route('**/api/sitter/plans', (route) => route.fulfill(json({ data: [] })));

    await step('方案設定頁：<main> 1.5rem + 頁面容器 1.5rem', page, async () => {
      await page.goto('/sitter/plans');
      await expect(page.getByTestId('sitter-plan-btn-add')).toBeVisible();
      await expectStandardPageInset(page, '方案設定頁');
    });
  });

  // 驗收 A5（N17 漏網）：飼主記事本檢視、帳號設定、通知中心的頁面容器過去是 padding '1rem 0' / '1.5rem 0'，
  // 內容邊界 469–810，其他頁 493–787。改用 PAGE_PADDING
  test('N17：記事本檢視 / 帳號設定 / 通知中心的內容左右內縮是 48px', async ({ page }) => {
    await mockDemoSession(page, 'client');
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
                    title: '餵食',
                    content: '早晚各一次',
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
    await page.route('**/api/auth/webauthn/credentials', (route) => route.fulfill(json([])));

    await step('飼主記事本檢視頁：內容左右內縮 48px', page, async () => {
      await page.goto('/care-notes/view/s-1/o-1');
      await expect(page.getByTestId('client-carenote-section-SERVICE')).toBeVisible();
      await expectStandardPageInset(page, '記事本檢視頁');
    });

    await step('帳號設定頁：內容左右內縮 48px', page, async () => {
      await page.goto('/account-settings');
      await expect(page.getByTestId('account-settings-deactivate-btn')).toBeVisible();
      await expectStandardPageInset(page, '帳號設定頁');
    });

    await step('通知中心：內容左右內縮 48px', page, async () => {
      await page.goto('/notifications');
      await expect(page.getByRole('heading', { name: '通知中心' })).toBeVisible();
      await expectStandardPageInset(page, '通知中心');
    });
  });

  test('U1：帳號設定「確認註銷」紅底白字，對比度 ≥ 4.5:1', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await page.route('**/api/auth/webauthn/credentials', (route) => route.fulfill(json([])));

    await step('帳號設定：展開註銷確認，「確認註銷」文字對紅底 ≥ 4.5:1', page, async () => {
      await page.goto('/account-settings');
      await page.getByTestId('account-settings-deactivate-btn').click();
      const btn = page.getByTestId('account-settings-deactivate-confirm-btn');
      await expect(btn).toHaveText('確認註銷');
      // 過去 --color-on-error 未定義，color 繼承到父層深紅字：深紅字配深紅底（驗收截圖新問題 2）
      const c = await contrastOf(btn);
      expect(c.backgroundImage).toBe('none');
      expect(c.backgroundColor).toBe('rgb(176, 37, 0)');
      expect(c.ratio, `確認註銷 ${c.color} on ${c.backgroundColor}`).toBeGreaterThanOrEqual(4.5);
    });
  });

  test('U2：金額的「$」與數字之間是不斷行空白，窄欄也不會斷成「$ / 1,200」', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockOwnerOrderDetail(page, ORDER_ID, 'CONFIRMED', 1200);

    await step('訂單詳情總額：U+00A0 分隔，壓到 1px 寬仍只佔一行', page, async () => {
      await page.goto(`/owner/orders/${ORDER_ID}`);
      const amount = page.getByTestId('order-total-amount');
      await expect(amount).toHaveText('$ 1,200');
      const probe = await amount.evaluate((el) => {
        const text = el.textContent ?? '';
        // 複製一份放進 1px 寬的容器，量它被迫換行後的行數（驗收截圖：D1 08/11/12「總額: $ / 1,200」）
        const box = document.createElement('div');
        box.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;';
        const span = document.createElement('span');
        span.style.font = getComputedStyle(el).font;
        span.textContent = text;
        box.appendChild(span);
        document.body.appendChild(box);
        const lines = span.getClientRects().length;
        box.remove();
        return { codePoints: [...text].map((c) => c.codePointAt(0)), lines };
      });
      expect(probe.codePoints[1], '「$」後面是 U+00A0').toBe(0xa0);
      expect(probe.lines, '1px 寬時仍不換行').toBe(1);
    });
  });

  // fix-batch12 U7（驗收截圖新問題 8）：KYC 徽章沿用深色主題的 rgba 底 + 淺色字，淺色主題下對比約 1.4~2.1
  test('U7：保母 KYC 頁各狀態徽章對比度 ≥ 4.5:1', async ({ page }) => {
    await mockDemoSession(page, 'sitter');
    let kycStatus = 'PENDING_REVIEW';
    await page.route('**/api/sitter/kyc/status', (route) =>
      route.fulfill(json({ data: { kycStatus, rejectReason: '', submittedAt: '' } }))
    );
    // 已送件的狀態會載入證件照預覽
    const mediaUrl = 'https://storage.example.test/kyc/u7-media.jpg?X-Goog-Signature=mock';
    await page.route('**/api/sitter/kyc/media/*', (route) =>
      route.fulfill(json({ data: { signedUrl: mediaUrl } }))
    );
    await serveFixtureImage(page, mediaUrl, 'kyc-selfie.jpg');
    const cases: Array<[string, string]> = [
      ['PENDING_REVIEW', '審核中'],
      ['VERIFIED', '已認證'],
      ['REJECTED', '被駁回'],
      ['SUSPENDED', '已停權']
    ];
    for (const [status, label] of cases) {
      kycStatus = status;
      await step(`KYC 徽章「${label}」對比度 ≥ 4.5:1`, page, async () => {
        await page.goto('/sitter/kyc');
        const badge = page.getByTestId('sitter-kyc-status-badge');
        await expect(badge).toHaveText(label);
        const c = await contrastOf(badge);
        expect(c.backgroundImage).toBe('none');
        expect(c.ratio, `${label} ${c.color} on ${c.backgroundColor}`).toBeGreaterThanOrEqual(4.5);
      });
    }
  });

  test('U7：訂單列表空狀態「目前沒有相關訂單」對比度 ≥ 4.5:1（保母 / 飼主）', async ({ page }) => {
    await mockDemoSession(page, 'sitter');
    await page.route('**/api/orders/sitter', (route) => route.fulfill(json([])));

    await step('保母訂單列表空狀態', page, async () => {
      await page.goto('/sitter/orders');
      const empty = page.getByTestId('sitter-orders-empty');
      await expect(empty).toHaveText('目前沒有相關訂單');
      const c = await contrastOf(empty);
      expect(c.ratio, `${c.color} on ${c.backgroundColor}`).toBeGreaterThanOrEqual(4.5);
    });
  });

  test('U7：飼主訂單列表空狀態「目前沒有相關訂單」對比度 ≥ 4.5:1', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockOwnerOrderList(page, []);

    await step('飼主訂單列表空狀態', page, async () => {
      await page.goto('/owner/orders');
      const empty = page.getByTestId('owner-orders-empty');
      await expect(empty).toHaveText('目前沒有相關訂單');
      const c = await contrastOf(empty);
      expect(c.ratio, `${c.color} on ${c.backgroundColor}`).toBeGreaterThanOrEqual(4.5);
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

  test('N12：飼主日誌檢視頁「已送出」徽章單行，不被標題擠成兩行', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await page.route('**/api/visits/v-n12/report', (route) =>
      route.fulfill(
        json({
          data: {
            reportId: 'r-n12',
            visitId: 'v-n12',
            status: 'SUBMITTED',
            content: '貓咪精神很好，已完成餵食與清砂。',
            submittedAt: '2026-10-24T08:00:00Z',
            media: [],
            isEditable: false,
            version: 2,
            visitStatus: 'DONE',
            isPurged: false
          }
        })
      )
    );

    await step('已送出日誌：徽章單行、在標題列右側', page, async () => {
      await page.goto('/visit-reports/view/v-n12');
      await expect(page.getByTestId('client-report-text-card')).toContainText('貓咪精神很好');
      const badge = page.getByTestId('client-report-status-badge');
      await expect(badge).toHaveText('已送出');
      await expectNoHorizontalOverflow(page);
      // 過去被擠成「已送 / 出」兩行：高度約兩倍、寬小於高的兩倍。單行時寬 > 高
      const box = (await badge.boundingBox())!;
      expect(box.width, '徽章寬應大於高').toBeGreaterThan(box.height);
      // line-height 可能是 normal（算不出數字），改以字級判斷：兩行文字高度至少約 2 倍字級
      const { fontSize, padding } = await badge.evaluate((el) => {
        const cs = getComputedStyle(el);
        return {
          fontSize: parseFloat(cs.fontSize),
          padding: parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom)
        };
      });
      expect(box.height - padding, '徽章文字只有一行').toBeLessThan(fontSize * 1.8);
    });
  });

  test('N18：保母主題日誌頁「暫存草稿」（primary-container 底）文字對比度 ≥ 4.5:1', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    await page.route('**/api/visits/v-n18/report', (route) =>
      route.fulfill(
        json({
          data: {
            reportId: 'r-n18',
            visitId: 'v-n18',
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

    await step('保母主題：「暫存草稿」黃底文字對比度', page, async () => {
      await page.goto('/visit-reports/manage/v-n18');
      const btn = page.getByTestId('sitter-report-save-draft-btn');
      await expect(btn).toHaveText('暫存草稿');
      const c = await contrastOf(btn);
      expect(c.backgroundImage, '暫存草稿不應疊漸層').toBe('none');
      // 過去 #765600 對 #ffbf00 約 4.1:1
      expect(c.ratio, `暫存草稿（${c.color} on ${c.backgroundColor}）`).toBeGreaterThanOrEqual(4.5);
    });
  });

  test('N18：飼主主題 primary-container 底的分頁文字對比度 ≥ 4.5:1', async ({ page }) => {
    await mockDemoSession(page, 'client');
    await mockOwnerOrderList(page);

    await step('飼主訂單管理：選中的「進行中」分頁', page, async () => {
      await page.goto('/owner/orders');
      const tab = page.getByTestId('owner-orders-tab-ongoing');
      await expect(tab).toHaveText('進行中');
      await expect(page.getByText('目前沒有相關訂單')).toBeVisible();
      const c = await contrastOf(tab);
      // 過去 #005e9f 對 #b3d4ff 約 4.44:1
      expect(c.ratio, `進行中分頁（${c.color} on ${c.backgroundColor}）`).toBeGreaterThanOrEqual(
        4.5
      );
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
      // 驗收截圖附帶觀察（.e2e-runs/20261024-1939）：沒被選中的切換鈕過去用 opacity 0.6 表示，
      // 白字壓在變淡的灰底上約 3:1。對比度要把 opacity 與後方底色混色算進去
      for (const name of ['切換為保母', '切換為飼主']) {
        const btn = page.getByRole('button', { name });
        await expect(btn).toBeVisible();
        const g = await effectiveGradientContrastOf(btn);
        expect(
          g.minRatio,
          `未選中「${name}」實際對比度（opacity ${g.opacity}、底 ${g.backdrop}、色標 ${g.stops.join(' / ') || '無漸層'}）`
        ).toBeGreaterThanOrEqual(4.5);
      }
    });
  });
});
