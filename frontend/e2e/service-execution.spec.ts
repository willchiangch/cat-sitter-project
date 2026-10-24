import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { assertNoUnmockedApi, mockDemoSession, serveFixtureImage } from './support/mockSession';
import { step, trackDialogs, expectDialog } from './support/evidence';

test.describe('Service Execution & Check-in Flow E2E', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  let visitStatus = 'PENDING';
  let reportStatus = 'DRAFT';
  let reportContent = '';

  test.beforeEach(async ({ page }) => {
    // 假 token 打真後端的 header API 會 401 → refresh 失敗清 token → 換頁被 RequireAuth 導去 /login，
    // 共用 mock 先攔通知 / KYC 狀態；頁面 API 仍由本檔 route（後註冊者優先）
    await mockDemoSession(page, 'sitter');
    // 每次測試前重置狀態
    visitStatus = 'PENDING';
    reportStatus = 'DRAFT';
    reportContent = '';

    // Mock Login
    await page.route(
      (url) => url.pathname.includes('/api/auth/login'),
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            accessToken: 'fake-token',
            refreshToken: 'fake-refresh',
            userId: '3d498178-14c0-4376-b81e-7fb02e615dda',
            email: 'sitter@test.com',
            fullName: '本地測試保母',
            role: 'SITTER'
          })
        });
      }
    );

    // Mock Visit Report GET & PUT
    await page.route('**/api/visits/2624511e-3f10-4376-b81e-7fb02e615dda/report', async (route) => {
      const method = route.request().method();
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 200,
            message: 'OK',
            data: {
              reportId: 'report-12345',
              visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
              status: reportStatus,
              content: reportContent,
              submittedAt: reportStatus === 'SUBMITTED' ? '2026-06-04T12:00:00Z' : null,
              media: [],
              isEditable: reportStatus === 'DRAFT',
              version: 1,
              visitStatus: visitStatus
            }
          })
        });
      } else if (method === 'PUT') {
        const body = JSON.parse(route.request().postData() || '{}');
        reportContent = body.content;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 200,
            message: '修改成功',
            data: {
              reportId: 'report-12345',
              visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
              status: reportStatus,
              content: reportContent,
              submittedAt: null,
              media: [],
              isEditable: true,
              version: 1,
              visitStatus: visitStatus
            }
          })
        });
      } else {
        await route.continue();
      }
    });
    // Mock Check-in API
    await page.route('**/api/visits/2624511e-3f10-4376-b81e-7fb02e615dda/start', async (route) => {
      visitStatus = 'IN_PROGRESS';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: '行程已開始',
          data: {
            visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
            visitStatus: 'IN_PROGRESS',
            orderStatus: 'IN_PROGRESS'
          }
        })
      });
    });

    // Mock Check-out API
    await page.route('**/api/visits/2624511e-3f10-4376-b81e-7fb02e615dda/end', async (route) => {
      visitStatus = 'DONE';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: '行程已結束',
          data: {
            visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
            visitStatus: 'DONE',
            finishedAt: '2026-06-04T12:00:00Z'
          }
        })
      });
    });

    // Mock Submit Report API
    await page.route(
      '**/api/visits/2624511e-3f10-4376-b81e-7fb02e615dda/report/submit',
      async (route) => {
        reportStatus = 'SUBMITTED';
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 200,
            message: '修改成功',
            data: null
          })
        });
      }
    );

    page.on('console', (msg) => console.log('BROWSER LOG:', msg.text()));
    page.on('pageerror', (err) => console.log('BROWSER ERROR:', err.message));

    await page.goto('/');
  });

  test('should complete the entire check-in, execution, check-out and submission flow successfully', async ({
    page
  }) => {
    const dialogs = trackDialogs(page);
    const putBodies: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'PUT' && r.url().endsWith('/report')) {
        putBodies.push(JSON.parse(r.postData() || '{}').content);
      }
    });

    await step('待執行：只有 Check-in，沒有日誌編輯區', page, async () => {
      // 1. 進入日誌回報頁面 (保母端)
      await page.getByRole('button', { name: '進入日誌回報 (保母端)' }).click();
      // 2. 驗證 PENDING 狀態之 UI
      await expect(page.locator('text=待執行')).toBeVisible();
      await expect(page.getByTestId('sitter-report-start-visit-btn')).toBeVisible();
      // 編輯日誌文字框在 PENDING 狀態不應顯示
      await expect(page.getByTestId('sitter-report-content-input')).not.toBeVisible();
    });

    await step('Check-in 後執行中：可編輯並暫存草稿', page, async () => {
      // 3. 點擊開始服務 (Check-in)
      await page.getByTestId('sitter-report-start-visit-btn').click();
      await expect(page.locator('text=已成功 Check-in，照護服務開始！')).toBeVisible();
      // 4. 驗證 IN_PROGRESS 狀態之 UI
      await expect(page.locator('text=執行中')).toBeVisible();
      await expect(page.getByTestId('sitter-report-content-input')).toBeVisible();
      await expect(page.getByTestId('sitter-report-end-visit-btn')).toBeVisible();
      // 5. 填寫文字日誌並暫存草稿
      await page.getByTestId('sitter-report-content-input').fill('貓咪精神很好，已完成餵食。');
      await page.getByTestId('sitter-report-save-draft-btn').click();
      await expect(page.locator('text=暫存草稿儲存成功')).toBeVisible();
      expect(reportContent).toBe('貓咪精神很好，已完成餵食。');
    });

    await step(
      'Check-out 後待送出日誌：文字框仍可編輯（與橫幅「請填寫文字日誌並送出」一致）',
      page,
      async () => {
        // 6. 點擊結束服務 (Check-out) - 需要確認 dialog
        await page.getByTestId('sitter-report-end-visit-btn').click();
        await expectDialog(dialogs, /確定要結束本次照護服務嗎？/);
        await expect(
          page.locator('text=已成功 Check-out，行程已完工！請填寫文字日誌並送出。')
        ).toBeVisible();
        // 7. 驗證 DONE 狀態之 UI：PRD-022 A.2 已結束且未逾期仍可編輯草稿並送出（驗收 N13：過去這裡是唯讀）
        await expect(page.locator('text=待送出日誌')).toBeVisible();
        await expect(page.getByTestId('sitter-report-content-input')).toBeEnabled();
        await expect(page.getByTestId('sitter-report-save-draft-btn')).toBeVisible();
        await expect(page.getByTestId('sitter-report-submit-btn')).toBeVisible();
      }
    );

    await step(
      'Check-out 後補寫內容直接送出：先暫存再送出，已完工 (已送出) 唯讀',
      page,
      async () => {
        // 8. 補一句話不按暫存、直接正式送出：內容要先存進草稿，不能被丟掉
        await page
          .getByTestId('sitter-report-content-input')
          .fill('貓咪精神很好，已完成餵食。離開前已補滿飲水。');
        await page.getByTestId('sitter-report-submit-btn').click();
        await expect(page.locator('text=照護日誌已成功送出！')).toBeVisible();
        expect(putBodies.at(-1)).toBe('貓咪精神很好，已完成餵食。離開前已補滿飲水。');
        // 9. 驗證最終完工狀態之 UI (只讀、狀態為已完工、無操作按鈕)
        await expect(page.locator('text=已完工 (已送出)')).toBeVisible();
        await expect(page.getByTestId('sitter-report-content-input')).toBeDisabled();
        await expect(page.getByTestId('sitter-report-submit-btn')).not.toBeVisible();
        await expect(page.getByTestId('sitter-report-save-draft-btn')).not.toBeVisible();
      }
    );
  });

  test('已完工但超過 24 小時未送出：唯讀、不顯示暫存與送出（PRD-022 AC-2.4）', async ({ page }) => {
    // 後端對逾期草稿回 isEditable:false（後註冊者優先，覆蓋 beforeEach 的 GET mock）
    await page.route('**/api/visits/2624511e-3f10-4376-b81e-7fb02e615dda/report', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'OK',
          data: {
            reportId: 'report-12345',
            visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
            status: 'DRAFT',
            content: '逾期未送出的草稿',
            submittedAt: null,
            media: [],
            isEditable: false,
            version: 1,
            visitStatus: 'DONE'
          }
        })
      })
    );

    await step('逾期草稿：已逾期 (唯讀)', page, async () => {
      await page.getByRole('button', { name: '進入日誌回報 (保母端)' }).click();
      await expect(page.locator('text=已逾期 (唯讀)')).toBeVisible();
      await expect(page.getByTestId('sitter-report-content-input')).toHaveValue('逾期未送出的草稿');
      await expect(page.getByTestId('sitter-report-content-input')).toBeDisabled();
      await expect(page.getByTestId('sitter-report-save-draft-btn')).toHaveCount(0);
      await expect(page.getByTestId('sitter-report-submit-btn')).toHaveCount(0);
    });
  });
  test('未暫存的文字在上傳媒體後的重新抓取中保留，媒體清單照常更新（fix-batch10 H4）', async ({
    page
  }) => {
    const visitId = '2624511e-3f10-4376-b81e-7fb02e615dda';
    const photoUrl = 'https://storage.googleapis.com/e2e-fake/report-photo-1.jpg';
    const media: object[] = [];
    let reportGets = 0;
    await serveFixtureImage(page, photoUrl, 'report-photo.jpg');
    // 覆蓋 beforeEach 的 GET：執行中、伺服器上已有舊草稿、媒體清單隨上傳成長（後註冊者優先）
    await page.route(`**/api/visits/${visitId}/report`, (route) => {
      reportGets++;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'OK',
          data: {
            reportId: 'report-12345',
            visitId,
            status: 'DRAFT',
            content: '伺服器上的舊草稿',
            submittedAt: null,
            media,
            isEditable: true,
            version: 1,
            visitStatus: 'IN_PROGRESS'
          }
        })
      });
    });
    await page.route(`**/api/visits/${visitId}/media`, (route) => {
      media.push({
        mediaId: `m-${media.length + 1}`,
        mediaUrl: photoUrl,
        mediaType: 'IMAGE',
        caption: '飲水補滿',
        version: 0,
        isPurged: false
      });
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'OK',
          data: { mediaId: 'm-1', mediaUrl: photoUrl }
        })
      });
    });

    await step('執行中：載入伺服器草稿，改寫成未暫存的新內容', page, async () => {
      await page.getByRole('button', { name: '進入日誌回報 (保母端)' }).click();
      const input = page.getByTestId('sitter-report-content-input');
      await expect(input).toHaveValue('伺服器上的舊草稿');
      await input.fill('剛寫好還沒暫存的日誌：貓咪吃了半碗乾糧。');
      await expect(page.getByText('📸 照護媒體清單 (0 筆)')).toBeVisible();
    });

    const getsBeforeUpload = reportGets;
    await step('上傳一張照片：媒體清單變 1 筆，文字框仍是未暫存內容', page, async () => {
      const fixture = path.join(
        path.dirname(fileURLToPath(import.meta.url)),
        'journeys/fixtures/report-photo.jpg'
      );
      await page.getByTestId('sitter-report-media-file-input').setInputFiles(fixture);
      await page.getByTestId('sitter-report-media-caption-input').fill('飲水補滿');
      await page.getByTestId('sitter-report-media-upload-btn').click();
      await expect(page.getByText('多媒體檔案上傳成功')).toBeVisible();
      // 媒體清單只可能來自上傳後重新抓取的 report，證明 refetch 真的發生過
      await expect(page.getByText('📸 照護媒體清單 (1 筆)')).toBeVisible();
      expect(reportGets).toBeGreaterThan(getsBeforeUpload);
      await expect(page.getByTestId('sitter-report-content-input')).toHaveValue(
        '剛寫好還沒暫存的日誌：貓咪吃了半碗乾糧。'
      );
    });
  });
});
