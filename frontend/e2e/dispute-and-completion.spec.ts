import { test, expect } from '@playwright/test';

test.describe('Dispute and Completion Flow', () => {
  let orderStatus = 'CONFIRMED';

  test.beforeEach(async ({ page }) => {
    orderStatus = 'CONFIRMED';

    // Mock Order Detail GET — 缺少此 mock 時 Cloud Run 回 401，axios interceptor 會重導至 /login
    await page.route('**/api/orders/a1023000-0000-0000-0000-000000000000', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'a1023000-0000-0000-0000-000000000000',
          ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
          sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
          status: orderStatus,
          totalAmount: 2400,
          adjustmentAmount: 0,
          disclaimerAgreed: true,
          paymentProofUrl: null,
          paymentProofLastFive: null,
          items: [],
          sitterPaymentInfo: null
        })
      });
    });

    // Mock 飼主訂單清單 GET — OwnerOrders 頁面改接真實清單 API 後需要一併攔截
    await page.route('**/api/orders/owner', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'a1023000-0000-0000-0000-000000000000',
            ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
            ownerName: '陳先生 (愛貓飼主)',
            sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
            sitterName: '本地測試保母',
            status: orderStatus,
            totalAmount: 2400,
            paymentProofUrl: null,
            paymentProofLastFive: null,
            scheduledDatesLabel: '2026-05-25 ~ 2026-05-29 (共 5 天)'
          }
        ])
      });
    });

    // 攔截結案 API
    await page.route('**/api/orders/*/complete**', async (route) => {
      orderStatus = 'COMPLETED';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '訂單已成功結案' })
      });
    });

    // 攔截提出爭議 API
    await page.route('**/api/orders/*/dispute**', async (route) => {
      orderStatus = 'DISPUTED';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已成功提交爭議申請' })
      });
    });

    // 攔截管理員調解 API — 二次驗證密碼改由後端核對，這裡模擬同樣的行為：
    // adminPassword 不等於 'password' 時回 403（刻意不用 401，避免撞上 axiosClient
    // 全域的 refresh-token 靜默重試機制），前端會顯示「二次驗證密碼錯誤」
    await page.route('**/api/orders/*/admin-resolve**', async (route) => {
      const body = route.request().postDataJSON() as { adminPassword?: string };
      if (body.adminPassword !== 'password') {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'FORBIDDEN', message: '權限不足' })
        });
        return;
      }
      orderStatus = 'COMPLETED';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '爭議已由管理員調解結案' })
      });
    });

    await page.goto('/');
  });

  test('should complete order from owner side', async ({ page }) => {
    // 切換為飼主角色 (訂單管理清單頁僅飼主角色可見)
    await page.getByRole('button', { name: '切換為飼主' }).click();

    // 進入飼主端訂單管理
    await page.getByRole('button', { name: '進入訂單管理 (飼主端)' }).click();
    await expect(page.locator('text=飼主訂單管理')).toBeVisible();

    // 點擊第一個卡片進入詳情
    await page.locator('text=ORDER #a1023000').first().click();
    await expect(page.locator('text=ORDER #a1023000')).toBeVisible();

    // 點擊結案按鈕
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('確定要將此訂單結案嗎');
      await dialog.accept();
    });
    
    const completeBtn = page.getByTestId('complete-order-btn');
    await completeBtn.click();

    // 檢查結案後的 banner
    await expect(page.getByTestId('completed-status-banner')).toBeVisible();
    await expect(page.getByTestId('completed-status-banner')).toContainText('訂單已順利結案');
  });

  test('should report dispute and resolve by admin', async ({ page }) => {
    // 進入訂單詳情
    await page.getByRole('button', { name: '直接進入訂單詳情 (飼主端)' }).click();
    
    // 點擊提出爭議
    await page.getByTestId('dispute-order-btn').click();
    await expect(page.getByTestId('dispute-modal')).toBeVisible();

    // 選擇類別並輸入描述
    await page.getByTestId('dispute-cat-未照約定打卡').click();
    await page.getByTestId('dispute-desc-textarea').fill('保母今天沒有在約定時間打卡。');

    // 提交爭議
    await page.getByTestId('dispute-submit-btn').click();

    // 檢查爭議後的 banner
    await expect(page.getByTestId('disputed-status-banner')).toBeVisible();
    await expect(page.getByTestId('disputed-status-banner')).toContainText('訂單正處於爭議調解中');

    // 返回 Demo 首頁
    await page.getByText('返回 Demo 首頁').click();

    // 切換至管理員
    await page.getByRole('button', { name: '切換為管理員' }).click();

    // 直接進入爭議調解
    await page.getByRole('button', { name: '直接進入爭議調解 (管理端)' }).click();

    // 填寫調解資訊
    await page.getByTestId('admin-resolve-amount').fill('1500');
    await page.getByTestId('admin-resolve-receipt').fill('https://receipt.jpg');
    await page.getByTestId('admin-resolve-reason').fill('已扣除未打卡時段之費用。');
    
    // 二次驗證密碼輸入錯誤
    await page.getByTestId('admin-resolve-password').fill('wrongpassword');
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('二次驗證密碼錯誤');
      await dialog.accept();
    });
    await page.getByTestId('admin-resolve-submit-btn').click();

    // 二次驗證密碼輸入正確
    await page.getByTestId('admin-resolve-password').fill('password');
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('爭議已順利調解結案');
      await dialog.accept();
    });
    await page.getByTestId('admin-resolve-submit-btn').click();

    // 驗證成功 banner
    await expect(page.getByTestId('admin-resolved-banner')).toBeVisible();
  });
});
