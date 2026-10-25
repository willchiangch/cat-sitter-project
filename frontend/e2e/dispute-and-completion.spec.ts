import { test, expect } from '@playwright/test';
import { step, trackDialogs, expectDialog } from './support/evidence';

test.describe('Dispute and Completion Flow', () => {
  let orderStatus = 'CONFIRMED';
  // fix-batch11 I1（BOLA）：記下結案 / 爭議請求，斷言前端不再帶 ownerId 等身分 query param
  let completeUrls: string[] = [];
  let disputeRequests: { url: string; body: unknown }[] = [];
  const queryKeys = (url: string) => [...new URL(url).searchParams.keys()];

  test.beforeEach(async ({ page }) => {
    orderStatus = 'CONFIRMED';
    completeUrls = [];
    disputeRequests = [];

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
          hasPaymentProof: false,
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
            hasPaymentProof: false,
            paymentProofLastFive: null,
            scheduledDatesLabel: '2026-05-25 ~ 2026-05-29 (共 5 天)'
          }
        ])
      });
    });

    // 攔截結案 API
    await page.route('**/api/orders/*/complete**', async (route) => {
      completeUrls.push(route.request().url());
      orderStatus = 'COMPLETED';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '訂單已成功結案' })
      });
    });

    // 攔截提出爭議 API
    await page.route('**/api/orders/*/dispute**', async (route) => {
      disputeRequests.push({ url: route.request().url(), body: route.request().postDataJSON() });
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
    // 驗收 N10：確認結案鈕只在所有行程都已結束時顯示，這裡的行程全部 DONE
    await page.route('**/api/orders/a1023000-0000-0000-0000-000000000000/visits', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'v-done-1', status: 'DONE', scheduledAt: '2026-05-25T00:00:00Z' },
          { id: 'v-done-2', status: 'DONE', scheduledAt: '2026-05-26T00:00:00Z' }
        ])
      })
    );
    const dialogs = trackDialogs(page);

    await step('飼主訂單詳情：行程全部已完工，顯示確認結案', page, async () => {
      // 切換為飼主角色 (訂單管理清單頁僅飼主角色可見)
      await page.getByRole('button', { name: '切換為飼主' }).click();

      // 進入飼主端訂單管理
      await page.getByRole('button', { name: '進入訂單管理 (飼主端)' }).click();
      await expect(page.locator('text=飼主訂單管理')).toBeVisible();

      // 點擊第一個卡片進入詳情
      await page.locator('text=ORDER #a1023000').first().click();
      await expect(page.locator('text=ORDER #a1023000')).toBeVisible();
      await expect(page.getByTestId('owner-order-visit-item')).toHaveCount(2);
      await expect(page.getByTestId('complete-order-btn')).toBeVisible();
    });

    await step('確認結案後顯示「訂單已順利結案」，請求不帶 ownerId', page, async () => {
      await page.getByTestId('complete-order-btn').click();
      await expectDialog(dialogs, /確定要將此訂單結案嗎/);
      await expect(page.getByTestId('completed-status-banner')).toBeVisible();
      await expect(page.getByTestId('completed-status-banner')).toContainText('訂單已順利結案');
      expect(completeUrls).toHaveLength(1);
      expect(queryKeys(completeUrls[0])).toEqual([]);
    });
  });

  test('should report dispute and resolve by admin', async ({ page }) => {
    const dialogs = trackDialogs(page);

    await step('飼主訂單詳情：開啟爭議表單並填寫', page, async () => {
      await page.getByRole('button', { name: '直接進入訂單詳情 (飼主端)' }).click();
      await page.getByTestId('dispute-order-btn').click();
      await expect(page.getByTestId('dispute-modal')).toBeVisible();
      await page.getByTestId('dispute-cat-未照約定打卡').click();
      await page.getByTestId('dispute-desc-textarea').fill('保母今天沒有在約定時間打卡。');
      await expect(page.getByTestId('dispute-desc-textarea')).toHaveValue(
        '保母今天沒有在約定時間打卡。'
      );
    });

    await step(
      '提交爭議後顯示調解中 banner，請求不帶 ownerId（只送類別與描述）',
      page,
      async () => {
        await page.getByTestId('dispute-submit-btn').click();
        await expectDialog(dialogs, /爭議申報成功/);
        await expect(page.getByTestId('disputed-status-banner')).toBeVisible();
        await expect(page.getByTestId('disputed-status-banner')).toContainText(
          '訂單正處於爭議調解中'
        );
        expect(disputeRequests).toHaveLength(1);
        expect(queryKeys(disputeRequests[0].url)).toEqual([]);
        expect(disputeRequests[0].body).toEqual({
          category: '未照約定打卡',
          description: '保母今天沒有在約定時間打卡。'
        });
      }
    );

    await step('管理員進入爭議調解頁並填寫調解資訊', page, async () => {
      await page.getByText('返回 Demo 首頁').click();
      await page.getByRole('button', { name: '切換為管理員' }).click();
      await page.getByRole('button', { name: '直接進入爭議調解 (管理端)' }).click();
      await page.getByTestId('admin-resolve-amount').fill('1500');
      await page.getByTestId('admin-resolve-receipt').fill('https://receipt.jpg');
      await page.getByTestId('admin-resolve-reason').fill('已扣除未打卡時段之費用。');
      await expect(page.getByTestId('admin-resolve-reason')).toHaveValue(
        '已扣除未打卡時段之費用。'
      );
    });

    // handleResolve 先 await API 再跳 alert（非同步對話框）；trackDialogs 記錄所有對話框，
    // expectDialog 只比對上一次斷言之後的新訊息，不會接到前一步的對話框
    await step('二次驗證密碼錯誤：提示錯誤，未結案', page, async () => {
      await page.getByTestId('admin-resolve-password').fill('wrongpassword');
      await page.getByTestId('admin-resolve-submit-btn').click();
      await expectDialog(dialogs, /二次驗證密碼錯誤/);
      await expect(page.getByTestId('admin-resolved-banner')).toHaveCount(0);
    });

    await step('二次驗證密碼正確：調解結案', page, async () => {
      await page.getByTestId('admin-resolve-password').fill('password');
      await page.getByTestId('admin-resolve-submit-btn').click();
      await expectDialog(dialogs, /爭議已順利調解結案/);
      await expect(page.getByTestId('admin-resolved-banner')).toBeVisible();
    });
  });
});
