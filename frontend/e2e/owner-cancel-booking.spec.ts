import { test, expect, type Page } from '@playwright/test';
import { step, trackDialogs, expectDialog } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession } from './support/mockSession';

/**
 * fix-batch10 H5：飼主付款前撤單（PRD-006 例外處理「飼主在保母審核中嘗試取消」）。
 * 可不可撤由後端 cancellableByOwner 決定（含補款階段排除），前端只照旗標顯示「取消預約」；
 * 保母端以 cancelledBy=OWNER 顯示「飼主已取消預約」。
 */
const orderId = 'c5000000-0000-0000-0000-0000000000a5';

interface OrderState {
  status: string;
  version: number;
  cancellableByOwner: boolean;
  cancelledBy?: string;
}

function orderBody(state: OrderState) {
  return JSON.stringify({
    id: orderId,
    ownerId: 'o-1',
    sitterId: 's-1',
    ownerName: '撤單測試飼主',
    status: state.status,
    version: state.version,
    totalAmount: 600,
    amountDue: 600,
    adjustmentAmount: 0,
    disclaimerAgreed: false,
    hasPaymentProof: false,
    paymentProofLastFive: null,
    items: [],
    sitterPaymentInfo: null,
    cancellableByOwner: state.cancellableByOwner,
    ...(state.cancelledBy ? { cancelledBy: state.cancelledBy } : {})
  });
}

async function mockOwnerOrder(page: Page, state: OrderState) {
  await page.route(`**/api/orders/${orderId}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: orderBody(state) })
  );
}

test.describe('PRD-006 飼主付款前撤單（fix-batch10 H5）', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('保母評估中：確認後撤單，送出畫面上的 version，轉為「您已取消此預約」且按鈕消失', async ({
    page
  }) => {
    await mockDemoSession(page, 'client');
    const dialogs = trackDialogs(page);
    const state: OrderState = { status: 'PENDING', version: 3, cancellableByOwner: true };
    await mockOwnerOrder(page, state);
    const cancelBodies: unknown[] = [];
    await page.route(`**/api/orders/${orderId}/cancel`, (route) => {
      cancelBodies.push(route.request().postDataJSON());
      Object.assign(state, {
        status: 'CANCELLED',
        version: 4,
        cancellableByOwner: false,
        cancelledBy: 'OWNER'
      });
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已取消預約' })
      });
    });

    await step('PENDING 訂單詳情顯示「取消預約」', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('order-status-badge')).toHaveText('待評估');
      await expect(page.getByTestId('owner-cancel-booking-btn')).toHaveText('取消預約');
      await expect(page.getByTestId('cancelled-status-banner')).toHaveCount(0);
    });

    await step('確認後撤單：已取消、顯示「您已取消此預約」、按鈕消失', page, async () => {
      await page.getByTestId('owner-cancel-booking-btn').click();
      await expectDialog(dialogs, /確定要取消這筆預約嗎？/);
      await expectDialog(dialogs, /^已取消預約$/);
      expect(cancelBodies).toEqual([{ version: 3 }]);
      await expect(page.getByTestId('order-status-badge')).toHaveText('已取消');
      await expect(page.getByTestId('cancelled-status-banner')).toHaveText('您已取消此預約');
      await expect(page.getByTestId('owner-cancel-booking-btn')).toHaveCount(0);
    });
  });

  test('保母剛報價（版本過期）：撤單 409，提示後重抓，畫面改為待付款且不可撤', async ({ page }) => {
    await mockDemoSession(page, 'client');
    const dialogs = trackDialogs(page);
    const state: OrderState = { status: 'PENDING', version: 1, cancellableByOwner: true };
    await mockOwnerOrder(page, state);
    await page.route(`**/api/orders/${orderId}/cancel`, (route) => {
      // 保母已先報價：後端以 version 不符回 409；重抓的詳情是 PENDING_PAYMENT，這裡模擬飼主另一分頁已上傳憑證（不可撤）
      Object.assign(state, { status: 'PENDING_PAYMENT', version: 2, cancellableByOwner: false });
      return route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'MSG_DATA_CONCURRENCY_CONFLICT',
          message: '內容已被更新，請重新整理後再試'
        })
      });
    });
    await page.route(`**/api/orders/${orderId}/visits`, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    );

    await step('PENDING 詳情顯示「取消預約」', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('owner-cancel-booking-btn')).toBeVisible();
    });

    await step('撤單 409：提示訊息、畫面重抓為待付款、沒有取消按鈕', page, async () => {
      await page.getByTestId('owner-cancel-booking-btn').click();
      await expectDialog(dialogs, /確定要取消這筆預約嗎？/);
      await expectDialog(dialogs, /內容已被更新，請重新整理後再試/);
      await expect(page.getByTestId('order-status-badge')).toHaveText('待付款');
      await expect(page.getByTestId('owner-cancel-booking-btn')).toHaveCount(0);
      await expect(page.getByTestId('cancelled-status-banner')).toHaveCount(0);
    });
  });

  for (const [label, status] of [
    ['訂單變更補款階段（待付款但後端判定不可撤）', 'PENDING_PAYMENT'],
    ['已付款待核帳', 'PAID'],
    ['已確認', 'CONFIRMED']
  ] as const) {
    test(`${label}：不顯示「取消預約」`, async ({ page }) => {
      await mockDemoSession(page, 'client');
      await mockOwnerOrder(page, { status, version: 5, cancellableByOwner: false });
      await page.route(`**/api/orders/${orderId}/visits`, (route) =>
        route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
      );

      await step(`${status} 詳情沒有取消按鈕`, page, async () => {
        await page.goto(`/owner/orders/${orderId}`);
        await expect(page.getByTestId('order-status-badge')).toHaveAttribute('data-status', status);
        await expect(page.getByTestId('owner-cancel-booking-btn')).toHaveCount(0);
      });
    });
  }

  test('保母端：飼主撤的單顯示「飼主已取消預約」，保母婉拒的不顯示；評估頁沒有接單按鈕', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    const rejectedId = 'c5000000-0000-0000-0000-0000000000b6';
    const summary = (id: string, cancelledBy?: string) => ({
      id,
      ownerId: 'o-1',
      ownerName: '撤單測試飼主',
      sitterId: 's-1',
      sitterName: '本地測試保母',
      status: 'CANCELLED',
      totalAmount: 600,
      amountDue: 600,
      hasPaymentProof: false,
      paymentProofLastFive: null,
      scheduledDatesLabel: '2026-12-01 (共 1 天)',
      ...(cancelledBy ? { cancelledBy } : {})
    });
    await page.route(
      (url) => url.pathname === '/api/orders/sitter',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([summary(orderId, 'OWNER'), summary(rejectedId)])
        })
    );
    await mockOwnerOrder(page, {
      status: 'CANCELLED',
      version: 4,
      cancellableByOwner: false,
      cancelledBy: 'OWNER'
    });

    await step('歷史訂單：飼主撤單顯示「飼主已取消預約」，保母婉拒的沒有', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-history').click();
      await expect(page.getByTestId(`sitter-order-owner-cancelled-${orderId}`)).toHaveText(
        '飼主已取消預約'
      );
      await expect(page.getByTestId(`sitter-order-owner-cancelled-${rejectedId}`)).toHaveCount(0);
    });

    // 驗收 A4：在歷史訂單看到兩張單（列表已載入）後再切評估中，避免「還沒載入就 0 筆」的空轉
    await step('評估中分頁：列表已載入，撤單與婉拒的單都不在這裡', page, async () => {
      await page.getByTestId('sitter-orders-tab-evaluating').click();
      await expect(page.getByTestId('sitter-orders-loading')).toHaveCount(0);
      await expect(page.getByTestId('sitter-orders-empty')).toBeVisible();
      await expect(page.getByTestId('sitter-orders-count')).toHaveText('共 0 筆');
      await expect(page.getByTestId('btn-go-order-eval')).toHaveCount(0);
    });

    await step('評估頁：顯示「飼主已取消預約」，沒有原價接受 / 報價按鈕', page, async () => {
      await page.goto(`/sitter/eval/${orderId}`);
      await expect(page.getByTestId('sitter-order-eval-not-pending')).toHaveText(
        '飼主已取消預約，無法在此頁面操作。'
      );
      await expect(page.getByTestId('sitter-order-eval-btn-accept-original')).toHaveCount(0);
      await expect(page.getByTestId('sitter-order-eval-btn-confirm')).toHaveCount(0);
    });
  });
});
