import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { step, trackDialogs, expectDialog } from './support/evidence';

const orderId = 'a1023000-0000-0000-0000-000000000000';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const proofImage = path.join(__dirname, 'journeys/fixtures/report-photo.jpg');
const signedRefundUrl =
  'https://storage.googleapis.com/mock-bucket/refund-proofs/2026-10-24/mock.jpg?X-Goog-Signature=abc';

type ModMock = Record<string, unknown> | null;

/** 各測試開頭指定變更請求的起始狀態（mock GET 讀這個變數） */
function modState(status: string, orderStatus: string, diffAmount: number): ModMock {
  return {
    id: 'b2034111-0000-0000-0000-000000000000',
    orderId,
    status,
    orderStatus,
    requestedBy: 'OWNER',
    diffAmount,
    newTotalAmount: 2400 + diffAmount,
    currentOrderTotalAmount: 2400,
    orderVersion: 1,
    dates: ['2026-05-26', '2026-05-27'],
    hasRefundProof: false
  };
}

let mod: ModMock = null;
let refundUploadContentType = '';

test.describe('Order Modification Flow', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    mod = modState('QUOTED', 'MODIFYING', -600);
    // 訂單詳情 GET — 變更精靈需要先讀取原始項目內容，才能在變更時保留正確欄位
    await page.route(`**/api/orders/${orderId}`, async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: orderId,
          ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
          ownerName: '陳先生',
          sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
          status: 'CONFIRMED',
          totalAmount: 2400,
          version: 1,
          adjustmentAmount: 0,
          disclaimerAgreed: true,
          items: [
            {
              category: 'BOOKING',
              serviceName: '基礎照顧',
              unitPrice: 500,
              quantity: 4,
              planId: '3d498178-14c0-4376-b81e-7fb02e615dda',
              dates: ['2026-05-25', '2026-05-26', '2026-05-27', '2026-05-28'],
              timesPerDay: 1
            }
          ]
        })
      });
    });

    // 進行中的變更請求 GET — 保母報價頁與飼主確認頁都需要真實資料，不再寫死測試 UUID。
    // 有狀態的 mock：報價/同意/上傳憑證/確認退款會推進 mod 狀態，頁面重新讀取後必須依新狀態收斂按鈕 (C2)
    await page.route(`**/api/orders/${orderId}/modification`, async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      if (!mod) {
        // 真實契約（N8 起）：沒有進行中的協商 → 204 No Content，不再是 409
        await route.fulfill({ status: 204, body: '' });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mod)
      });
    });

    // 攔截變更申請 API
    await page.route(`**/api/orders/${orderId}/modify**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '變更請求已提交' })
      });
    });

    // 攔截保母報價 API
    await page.route(`**/api/orders/${orderId}/modification/quote**`, async (route) => {
      const body = route.request().postDataJSON() as { newTotalAmount: number };
      if (mod) {
        mod = {
          ...mod,
          status: 'QUOTED',
          newTotalAmount: body.newTotalAmount,
          diffAmount: body.newTotalAmount - 2400
        };
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '報價已送出' })
      });
    });

    // 攔截保母拒絕變更 API
    await page.route(`**/api/orders/${orderId}/modification/reject**`, async (route) => {
      mod = null;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已拒絕此變更請求' })
      });
    });

    // 攔截上傳憑證 API
    await page.route(`**/api/orders/${orderId}/modification/refund-proof**`, async (route) => {
      // C4：退款憑證必須以 multipart 上傳圖片，不再送網址字串
      refundUploadContentType = route.request().headers()['content-type'] ?? '';
      if (mod) mod = { ...mod, hasRefundProof: true };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '退款憑證已成功上傳' })
      });
    });

    // 退款憑證簽名網址與圖片本體 (SD-016 §2.5)
    await page.route(
      `**/api/orders/${orderId}/modification/refund-proof/signed-url`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ signedUrl: signedRefundUrl, expiresInSeconds: 600 })
        });
      }
    );
    await page.route('https://storage.googleapis.com/mock-bucket/**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'image/jpeg', path: proofImage });
    });

    // 攔截飼主確認變更 API
    await page.route(`**/api/orders/${orderId}/modification/confirm**`, async (route) => {
      if (mod) {
        const diff = mod.diffAmount as number;
        mod = {
          ...mod,
          status: 'M_DONE',
          orderStatus: diff < 0 ? 'REFUND_VERIFY' : diff > 0 ? 'PENDING_PAYMENT' : 'CONFIRMED'
        };
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '變更已生效' })
      });
    });

    // 攔截確認退款 API
    await page.route(`**/api/orders/${orderId}/modification/refund-confirm**`, async (route) => {
      mod = null;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'SUCCESS', message: '已確認收到退款' })
      });
    });

    // 單一導覽：後續都靠 React Router client-side 導覽，避免像 order-eval.spec.ts
    // 曾遇到的「同帳號連續兩次真實登入」併發衝突
    await page.goto('/demo');
  });

  test('should propose modification and enforce date ranges', async ({ page }) => {
    await page.getByRole('button', { name: '直接進入變更精靈 (飼主/保母)' }).click();

    // 1. 測試超出範圍的日期防呆 (PLAN_NOT_IN_RANGE)
    await page.getByTestId('modify-dates-input').fill('2026-06-01');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('PLAN_NOT_IN_RANGE');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();

    // 2. 測試正確區間的日期
    await page.getByTestId('modify-dates-input').fill('2026-05-26, 2026-05-27');
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('變更申請已成功提交');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();
    await expect(page.getByTestId('modification-success-banner')).toBeVisible();
  });

  test('should support cancellation by submitting empty dates', async ({ page }) => {
    await page.getByRole('button', { name: '直接進入變更精靈 (飼主/保母)' }).click();

    // 勾選取消預約
    await page.getByTestId('cancel-checkbox').check();
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('變更申請已成功提交');
      await dialog.accept();
    });
    await page.getByTestId('modify-submit-btn').click();
    await expect(page.getByTestId('modification-success-banner')).toBeVisible();
  });

  test('should review quote from sitter side, then only reject remains (no re-quote, no refund section)', async ({
    page
  }) => {
    mod = modState('PENDING_REVIEW', 'MODIFYING', -600);
    const dialogs = trackDialogs(page);

    await step('保母進入報價頁：待報價階段只有報價表單與拒絕，沒有退款區塊', page, async () => {
      await page.getByRole('button', { name: '切換為保母' }).click();
      await page.getByRole('button', { name: '直接進入變更報價 (保母端)' }).click();
      // 頁面已從真實 API 帶入保母的報價金額
      await expect(page.getByTestId('quote-amount-input')).toHaveValue('1800');
      await expect(page.getByTestId('reject-mod-btn')).toBeVisible();
      await expect(page.getByTestId('refund-proof-section')).toHaveCount(0);
    });

    await step('送出報價後報價表單消失，只剩拒絕；仍無退款區塊（P7 迴歸）', page, async () => {
      await page.getByTestId('quote-amount-input').fill('1800');
      await page.getByTestId('quote-submit-btn').click();
      await expectDialog(dialogs, /報價已成功送出/);
      await expect(page.getByTestId('quote-success-banner')).toHaveText(
        /報價已送出 \(總額: \$ 1,800\)/
      );
      await expect(page.getByTestId('quote-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('quote-amount-input')).toHaveCount(0);
      await expect(page.getByTestId('reject-mod-btn')).toBeVisible();
      await expect(page.getByTestId('refund-proof-section')).toHaveCount(0);
    });
  });

  test('should upload refund proof from sitter side only after owner agreed to a refund', async ({
    page
  }) => {
    mod = modState('M_DONE', 'REFUND_VERIFY', -600);
    const dialogs = trackDialogs(page);

    await step('退款階段：報價/拒絕都已不可操作，只剩退款憑證上傳', page, async () => {
      await page.getByRole('button', { name: '切換為保母' }).click();
      await page.getByRole('button', { name: '直接進入變更報價 (保母端)' }).click();
      await expect(page.getByTestId('mod-quote-readonly-summary')).toContainText('應退款 $ 600');
      await expect(page.getByTestId('quote-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('reject-mod-btn')).toHaveCount(0);
      await expect(page.getByTestId('refund-proof-section')).toBeVisible();
    });

    await step('上傳退款憑證圖片後表單消失，顯示簽名網址預覽（C4）', page, async () => {
      // C4：不再有手填網址欄位
      await expect(page.getByTestId('refund-proof-input')).toHaveCount(0);
      await page.getByTestId('refund-proof-file-input').setInputFiles(proofImage);
      await page.getByTestId('refund-proof-submit-btn').click();
      await expectDialog(dialogs, /退款憑證已成功上傳/);
      expect(refundUploadContentType).toMatch(/^multipart\/form-data/);
      await expect(page.getByTestId('refund-proof-success-banner')).toBeVisible();
      await expect(page.getByTestId('refund-proof-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('refund-proof-preview')).toHaveAttribute(
        'src',
        signedRefundUrl
      );
    });
  });

  test('should show no refund section and no actions to sitter on supplement branch', async ({
    page
  }) => {
    mod = modState('M_DONE', 'PENDING_PAYMENT', 600);

    await step('補款階段：保母頁唯讀，無報價/拒絕/退款區塊', page, async () => {
      await page.getByRole('button', { name: '切換為保母' }).click();
      await page.getByRole('button', { name: '直接進入變更報價 (保母端)' }).click();
      await expect(page.getByTestId('mod-quote-readonly-summary')).toContainText('應補款 $ 600');
      await expect(page.getByTestId('quote-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('reject-mod-btn')).toHaveCount(0);
      await expect(page.getByTestId('refund-proof-section')).toHaveCount(0);
    });
  });

  test('should allow sitter to reject the modification request', async ({ page }) => {
    await page.getByRole('button', { name: '切換為保母' }).click();
    await page.getByRole('button', { name: '直接進入變更報價 (保母端)' }).click();

    await expect(page.getByTestId('reject-mod-btn')).toBeVisible();

    // 一次點擊會依序觸發 window.confirm() 與成功後的 alert()，用持續監聽收集訊息順序
    const dialogMessages: string[] = [];
    page.on('dialog', async (dialog) => {
      dialogMessages.push(dialog.message());
      await dialog.accept();
    });

    await page.getByTestId('reject-mod-btn').click();
    await expect(page.getByTestId('reject-success-banner')).toBeVisible();

    expect(dialogMessages[0]).toContain('確定要拒絕此變更請求');
    expect(dialogMessages[1]).toContain('已拒絕此變更請求');
  });

  test('should confirm modification with zero-trust checks from owner side', async ({ page }) => {
    const dialogs = trackDialogs(page);

    await step('飼主進入確認頁：已報價階段只有同意表單，沒有退款區塊', page, async () => {
      await page.getByRole('button', { name: '切換為飼主' }).click();
      await page.getByRole('button', { name: '直接進入變更確認 (飼主端)' }).click();
      // 頁面已從真實 API 帶入保母報價差額
      await expect(page.getByTestId('mod-confirm-diff-amount')).toContainText('-600');
      await expect(page.getByTestId('refund-confirm-section')).toHaveCount(0);
    });

    await step('零信任對帳：金額不一致時擋下', page, async () => {
      await page.getByTestId('agreed-diff-input').fill('-500');
      await page.getByTestId('confirm-submit-btn').click();
      await expectDialog(dialogs, /零信任對帳警告/);
      await expect(page.getByTestId('confirm-submit-btn')).toBeVisible();
    });

    await step(
      '金額相符時確認變更，同意表單消失；保母尚未上傳憑證前不可確認退款',
      page,
      async () => {
        await page.getByTestId('agreed-diff-input').fill('-600');
        await page.getByTestId('confirm-submit-btn').click();
        await expectDialog(dialogs, /變更確認成功/);
        await expect(page.getByTestId('confirm-success-banner')).toBeVisible();
        await expect(page.getByTestId('confirm-submit-btn')).toHaveCount(0);
        await expect(page.getByTestId('refund-confirm-section')).toBeVisible();
        await expect(page.getByTestId('refund-awaiting-proof')).toBeVisible();
        await expect(page.getByTestId('refund-confirm-btn')).toHaveCount(0);
      }
    );

    await step('保母上傳憑證後重新進頁，確認收到線下退款，按鈕消失', page, async () => {
      mod = { ...mod, hasRefundProof: true };
      // SPA 內返回再前進讓頁面重新掛載、重新讀取（不整頁重載，避免再觸發一次種子帳號登入）
      await page.goBack();
      await expect(page).toHaveURL(/\/demo$/);
      await page.goForward();
      await expect(page.getByTestId('refund-confirm-btn')).toBeVisible();
      await expect(page.getByTestId('confirm-submit-btn')).toHaveCount(0);
      // C4：飼主端以簽名網址 <img> 顯示退款憑證，不做成可點的外部連結
      await expect(page.getByTestId('refund-proof-preview')).toHaveAttribute(
        'src',
        signedRefundUrl
      );
      await expect(page.getByTestId('refund-confirm-section').locator('a')).toHaveCount(0);
      await page.getByTestId('refund-confirm-btn').click();
      await expectDialog(dialogs, /已確認收到退款/);
      await expect(page.getByTestId('refund-confirm-success-banner')).toBeVisible();
      await expect(page.getByTestId('refund-confirm-btn')).toHaveCount(0);
    });

    await step('結清後重新進頁：無任何可操作按鈕', page, async () => {
      await page.goBack();
      await expect(page).toHaveURL(/\/demo$/);
      await page.goForward();
      // N8：結清是正常終態，顯示中性空狀態（不是紅色錯誤字）
      await expect(page.getByTestId('mod-confirm-empty')).toHaveText(
        '目前沒有進行中的變更協商：先前的協商已結清或已拒絕，或這筆訂單尚未發起變更。'
      );
      await expect(page.getByTestId('mod-confirm-empty')).toHaveCSS('color', 'rgb(90, 92, 91)');
      await expect(page.getByTestId('mod-confirm-error')).toHaveCount(0);
      await expect(page.getByTestId('confirm-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('refund-confirm-btn')).toHaveCount(0);
    });
  });

  test('should hide refund section on supplement branch from owner side', async ({ page }) => {
    mod = modState('QUOTED', 'MODIFYING', 600);
    const dialogs = trackDialogs(page);

    await step('飼主同意補款變更後只剩「前往補款」，沒有退款區塊', page, async () => {
      await page.getByRole('button', { name: '切換為飼主' }).click();
      await page.getByRole('button', { name: '直接進入變更確認 (飼主端)' }).click();
      await expect(page.getByTestId('mod-confirm-diff-amount')).toHaveText(
        /^\$\s?600\s*\(負值為退款\)$/
      );
      await page.getByTestId('confirm-submit-btn').click();
      await expectDialog(dialogs, /變更確認成功/);
      await expect(page.getByTestId('confirm-success-banner')).toContainText('應補款 $ 600');
      await expect(page.getByTestId('confirm-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('mod-confirm-go-pay-btn')).toBeVisible();
      await expect(page.getByTestId('refund-confirm-section')).toHaveCount(0);
      await expect(page.getByTestId('refund-confirm-btn')).toHaveCount(0);
    });
  });
});
