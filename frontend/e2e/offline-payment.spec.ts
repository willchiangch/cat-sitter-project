import { test, expect } from '@playwright/test';

test.describe('Offline Payment & Sitter Payment Info Flow E2E', () => {
  let orderStatus = 'PENDING_PAYMENT';
  let sitterPaymentInfo: any = {
    bankCode: '822',
    bankBranch: '忠孝分行',
    bankAccount: '123456789012',
    bankPayeeName: '本地測試保母'
  };
  let paymentProofLastFive = '';
  let paymentProofUrl = '';

  test.beforeEach(async ({ page }) => {
    // 每次測試前重置狀態
    orderStatus = 'PENDING_PAYMENT';
    sitterPaymentInfo = {
      bankCode: '822',
      bankBranch: '忠孝分行',
      bankAccount: '123456789012',
      bankPayeeName: '本地測試保母'
    };
    paymentProofLastFive = '';
    paymentProofUrl = '';

    // Mock Login
    await page.route(url => url.pathname.includes('/api/auth/login'), async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'fake-initial-token',
          refreshToken: 'fake-initial-refresh',
          userId: '1031efbc-583a-4062-9a35-15706a3384c6',
          email: 'owner@test.com',
          fullName: '愛貓飼主',
          role: 'CLIENT'
        })
      });
    });

    // Mock Sitter Payment Info GET
    await page.route('**/api/sitter/payment-info', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(sitterPaymentInfo)
        });
      } else if (route.request().method() === 'PUT') {
        const body = JSON.parse(route.request().postData() || '{}');
        sitterPaymentInfo = { ...sitterPaymentInfo, ...body };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ status: 'SUCCESS', message: '銀行轉帳帳戶資訊更新成功！' })
        });
      }
    });

    // Mock Order Detail GET
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
          disclaimerAgreed: orderStatus !== 'PENDING_PAYMENT',
          paymentProofUrl: paymentProofUrl,
          paymentProofLastFive: paymentProofLastFive,
          items: [],
          sitterPaymentInfo: orderStatus === 'CONFIRMED' ? null : sitterPaymentInfo
        })
      });
    });

    // Mock 保母訂單清單 GET — SitterOrders 頁面改接真實清單 API 後需要一併攔截
    await page.route('**/api/orders/sitter', async (route) => {
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
            paymentProofUrl: paymentProofUrl,
            paymentProofLastFive: paymentProofLastFive,
            scheduledDatesLabel: '2026-05-25 ~ 2026-05-29 (共 5 天)'
          }
        ])
      });
    });

    // Mock Upload Payment Proof
    await page.route('**/api/orders/a1023000-0000-0000-0000-000000000000/payment-proof', async (route) => {
      orderStatus = 'PAID';
      
      // 從 multipart request 取得 lastFive
      const request = route.request();
      const postData = request.postData();
      if (postData) {
        const match = postData.match(/name="lastFive"[\r\n\s]+(\d+)/);
        if (match) {
          paymentProofLastFive = match[1];
        } else {
          paymentProofLastFive = '54321';
        }
      } else {
        paymentProofLastFive = '54321';
      }
      
      paymentProofUrl = 'https://picsum.photos/400/300';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '付款憑證提交成功，等待保母確認！' })
      });
    });

    // Mock Verify Payment
    await page.route('**/api/orders/a1023000-0000-0000-0000-000000000000/verify-payment', async (route) => {
      orderStatus = 'CONFIRMED';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '確認成功' })
      });
    });

    // Mock Reject Payment
    await page.route('**/api/orders/a1023000-0000-0000-0000-000000000000/reject-payment', async (route) => {
      orderStatus = 'PENDING_PAYMENT';
      paymentProofLastFive = '';
      paymentProofUrl = '';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已駁回憑證' })
      });
    });

    await page.goto('/');
  });

  test('should manage sitter payment info settings successfully', async ({ page }) => {
    // 進入收款設定 (保母端)
    await page.getByTestId('btn-go-sitter-payment').click();
    await expect(page.locator('h2')).toContainText('銀行收款資訊設定');

    // 驗證讀取初始資訊
    await expect(page.getByTestId('input-sitter-bank-code')).toHaveValue('822');
    await expect(page.getByTestId('input-sitter-bank-branch')).toHaveValue('忠孝分行');
    await expect(page.getByTestId('input-sitter-bank-account')).toHaveValue('123456789012');
    await expect(page.getByTestId('input-sitter-bank-payee')).toHaveValue('本地測試保母');

    // 填寫不合規資料 (銀行代碼非3碼)
    await page.getByTestId('input-sitter-bank-code').fill('12');
    await page.getByTestId('btn-save-bank-info').click();
    await expect(page.getByTestId('sitter-bank-message')).toContainText('銀行代碼必須為 3 碼數字');

    // 填寫合規資料並儲存
    await page.getByTestId('input-sitter-bank-code').fill('007');
    await page.getByTestId('input-sitter-bank-branch').fill('信義分行');
    await page.getByTestId('input-sitter-bank-account').fill('9876543210987');
    await page.getByTestId('input-sitter-bank-payee').fill('新測試保母');
    
    await page.getByTestId('btn-save-bank-info').click();
    await expect(page.getByTestId('sitter-bank-message')).toContainText('銀行轉帳帳戶資訊更新成功！');

    // 驗證更新後的變數
    expect(sitterPaymentInfo.bankCode).toBe('007');
    expect(sitterPaymentInfo.bankAccount).toBe('9876543210987');
  });

  test('should display bank info and allow owner to upload proof with validations', async ({ page }) => {
    // 直接進入訂單詳情 (飼主端)
    await page.getByRole('button', { name: '直接進入訂單詳情 (飼主端)' }).click();
    
    // 1. 驗證顯示保母收款帳戶
    await expect(page.getByTestId('bank-info-container')).toBeVisible();
    await expect(page.getByTestId('bank-code-text')).toContainText('822');
    await expect(page.getByTestId('bank-account-text')).toContainText('123456789012');

    // 2. 校驗：未勾選免責聲明時提交
    await page.getByTestId('input-payment-last-five').fill('12345');
    
    // 建立一個 mock file 來上傳
    const fileChooserPromise = page.waitForEvent('filechooser');
    await page.getByTestId('input-payment-file').click();
    const fileChooser = await fileChooserPromise;
    await fileChooser.setFiles({
      name: 'proof.png',
      mimeType: 'image/png',
      buffer: Buffer.from('fake-image-data')
    });

    await page.getByTestId('btn-submit-payment-proof').click();
    await expect(page.locator('text=必須勾選並同意線下交易免責聲明')).toBeVisible();

    // 3. 校驗：後五碼格式錯誤時提交
    await page.getByTestId('checkbox-disclaimer-agreed').check();
    await page.getByTestId('input-payment-last-five').fill('123'); // 格式不符 (非5碼)
    await page.getByTestId('btn-submit-payment-proof').click();
    await expect(page.locator('text=轉帳帳號後五碼必須為 5 位數字')).toBeVisible();

    // 4. 合規提交憑證
    await page.getByTestId('input-payment-last-five').fill('54321');
    
    // 監聽 window.alert (正確的時序處理)
    const [dialog] = await Promise.all([
      page.waitForEvent('dialog'),
      page.getByTestId('btn-submit-payment-proof').click()
    ]);
    expect(dialog.message()).toContain('付款憑證提交成功，等待保母確認！');
    await dialog.accept();

    // 5. 驗證狀態是否流轉為 PAID 並顯示預覽
    await expect(page.locator('text=✓ 已提交付款憑證')).toBeVisible();
    await expect(page.locator('text=54321')).toBeVisible();
    expect(orderStatus).toBe('PAID');
  });

  test('should allow sitter to verify and reject payment proof', async ({ page }) => {
    // 模擬已付款狀態
    orderStatus = 'PAID';
    paymentProofLastFive = '99999';
    paymentProofUrl = 'https://picsum.photos/400/300';

    // 補齊 TS-007-1 Step 1.2: 驗證保母端進入詳情頁面時，PAID 狀態仍能看到 sitterPaymentInfo 的相關資訊
    await page.getByRole('button', { name: '直接進入訂單詳情 (飼主端)' }).click();
    await expect(page.getByTestId('bank-info-container')).toBeVisible();
    await expect(page.getByTestId('bank-code-text')).toContainText('822');
    await expect(page.getByTestId('bank-account-text')).toContainText('123456789012');

    // 返回 Demo 首頁
    await page.getByRole('button', { name: '返回 Demo 首頁' }).click();

    // 進入訂單管理 (保母端)
    await page.getByRole('button', { name: '進入訂單管理 (保母端)' }).click();
    
    // 切換至進行中 tab
    await page.getByTestId('sitter-orders-tab-ongoing').click();

    // 驗證憑證核對面板顯示
    await expect(page.getByTestId('payment-verification-panel')).toBeVisible();
    await expect(page.getByTestId('payment-verification-panel')).toContainText('99999');

    // 1. 測試駁回憑證流程 - 異常（未寫理由）
    await page.getByTestId('btn-reject-payment').click();
    
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('請填寫退回原因');
      await dialog.accept();
    });
    await page.getByTestId('btn-submit-reject').click();

    // 2. 測試駁回憑證流程 - 正常駁回
    await page.getByTestId('input-reject-reason').fill('沒收到款項，後五碼不對');
    const [dialogReject] = await Promise.all([
      page.waitForEvent('dialog'),
      page.getByTestId('btn-submit-reject').click()
    ]);
    expect(dialogReject.message()).toContain('已成功駁回該憑證，訂單已退回待付款狀態。');
    await dialogReject.accept();

    // 驗證狀態退回
    expect(orderStatus).toBe('PENDING_PAYMENT');
    expect(paymentProofLastFive).toBe('');

    // 3. 測試確認入帳流程
    // 重新置為 PAID
    orderStatus = 'PAID';
    paymentProofLastFive = '88888';
    await page.goto('/');
    await page.getByRole('button', { name: '進入訂單管理 (保母端)' }).click();
    await page.getByTestId('sitter-orders-tab-ongoing').click();

    const [dialogVerify] = await Promise.all([
      page.waitForEvent('dialog'),
      page.getByTestId('btn-verify-payment').click()
    ]);
    expect(dialogVerify.message()).toContain('入帳核對確認成功！');
    await dialogVerify.accept();

    // 驗證狀態流轉為 CONFIRMED
    expect(orderStatus).toBe('CONFIRMED');
  });

  test('should hide bank info to owner when status is CONFIRMED', async ({ page }) => {
    // 設為已確認狀態，保母收款帳戶應該過濾不顯示
    orderStatus = 'CONFIRMED';
    
    await page.getByRole('button', { name: '直接進入訂單詳情 (飼主端)' }).click();

    // 驗證保母收款帳戶資訊不顯示
    await expect(page.getByTestId('bank-info-container')).not.toBeVisible();
  });
});
