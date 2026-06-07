import { test, expect } from '@playwright/test';

test.describe('Service Execution & Check-in Flow E2E', () => {
  let visitStatus = 'PENDING';
  let reportStatus = 'DRAFT';
  let reportContent = '';

  test.beforeEach(async ({ page }) => {
    // 每次測試前重置狀態
    visitStatus = 'PENDING';
    reportStatus = 'DRAFT';
    reportContent = '';

    // Mock Login
    await page.route(url => url.pathname.includes('/api/auth/login'), async (route) => {
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
    });

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
    await page.route('**/api/visits/2624511e-3f10-4376-b81e-7fb02e615dda/report/submit', async (route) => {
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
    });

    page.on('console', msg => console.log('BROWSER LOG:', msg.text()));
    page.on('pageerror', err => console.log('BROWSER ERROR:', err.message));

    await page.goto('/');
  });

  test('should complete the entire check-in, execution, check-out and submission flow successfully', async ({ page }) => {
    // 1. 進入日誌回報頁面 (保母端)
    await page.getByRole('button', { name: '進入日誌回報 (保母端)' }).click();

    // 2. 驗證 PENDING 狀態之 UI
    await expect(page.locator('text=待執行')).toBeVisible();
    await expect(page.getByTestId('sitter-report-start-visit-btn')).toBeVisible();
    // 編輯日誌文字框在 PENDING 狀態不應顯示
    await expect(page.getByTestId('sitter-report-content-input')).not.toBeVisible();

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

    // 6. 點擊結束服務 (Check-out) - 需要確認 dialog
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('確定要結束本次照護服務嗎？');
      await dialog.accept();
    });
    await page.getByTestId('sitter-report-end-visit-btn').click();
    await expect(page.locator('text=已成功 Check-out，行程已完工！請填寫文字日誌並送出。')).toBeVisible();

    // 7. 驗證 DONE 狀態之 UI (唯讀、出現正式送出按鈕)
    await expect(page.locator('text=待送出日誌')).toBeVisible();
    await expect(page.getByTestId('sitter-report-content-input')).toBeDisabled();
    await expect(page.getByTestId('sitter-report-submit-btn')).toBeVisible();

    // 8. 點擊正式送出日誌
    await page.getByTestId('sitter-report-submit-btn').click();
    await expect(page.locator('text=照護日誌已成功送出！')).toBeVisible();

    // 9. 驗證最終完工狀態之 UI (只讀、狀態為已完工、無操作按鈕)
    await expect(page.locator('text=已完工 (已送出)')).toBeVisible();
    await expect(page.getByTestId('sitter-report-submit-btn')).not.toBeVisible();
    await expect(page.getByTestId('sitter-report-save-draft-btn')).not.toBeVisible();
  });
});
