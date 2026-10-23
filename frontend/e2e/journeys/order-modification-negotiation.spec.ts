import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect, type Page } from '@playwright/test';
import {
  bootstrapConfirmedOrder,
  setBrowserAuth,
  awaitOrderStatus,
  expectSignedPaymentProof
} from './helpers';
import { step, apiStep, snap, trackDialogs, expectDialog } from '../support/evidence';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** C2 迴歸 (P7)：協商結清後直接打網址重新進入兩個協商頁，不得再出現任何可操作按鈕 */
async function expectNegotiationPagesClosed(ownerPage: Page, sitterPage: Page, orderId: string) {
  await step('結清後保母重新進入報價頁：無任何可操作按鈕', sitterPage, async () => {
    await sitterPage.goto(`/sitter/orders/${orderId}/quote`);
    await expect(sitterPage.getByTestId('mod-quote-error')).toBeVisible({ timeout: 10000 });
    await expect(sitterPage.getByTestId('quote-submit-btn')).toHaveCount(0);
    await expect(sitterPage.getByTestId('reject-mod-btn')).toHaveCount(0);
    await expect(sitterPage.getByTestId('refund-proof-submit-btn')).toHaveCount(0);
  });
  await step('結清後飼主重新進入確認頁：無任何可操作按鈕', ownerPage, async () => {
    await ownerPage.goto(`/owner/orders/${orderId}/modification-confirm`);
    await expect(ownerPage.getByTestId('mod-confirm-error')).toBeVisible({ timeout: 10000 });
    await expect(ownerPage.getByTestId('confirm-submit-btn')).toHaveCount(0);
    await expect(ownerPage.getByTestId('refund-confirm-btn')).toHaveCount(0);
  });
}

/**
 * TS-JOURNEY-04：訂單變更協商，補款/退款兩分支。
 * 打真後端 + 真資料庫（正式站），且雙方都是透過本次新補上的「申請訂單變更」/「處理訂單變更協商」/
 * 「查看並確認訂單變更」三個按鈕真實點入，不是繞過去直接打 URL。
 */
test.describe('Journey D: 訂單變更協商 (TS-JOURNEY-04)', () => {
  test.setTimeout(120000);

  test('補款分支：飼主增加天數 -> 保母報價 -> 飼主確認 -> 補款 -> 保母核對入帳，全流程可正常運作', async ({
    page,
    request,
    browser
  }) => {
    const { sitter, owner, orderId } = await apiStep('前置：兜出一筆單天 CONFIRMED 訂單', async () => {
      return bootstrapConfirmedOrder(request, 'd1', 'Journey方案D1');
    });
    // B2 迴歸用：首次付款（bootstrap 以後五碼 12345 付款並核帳）的紀錄，補款後不得被覆寫
    const initialPayment = await apiStep('前置：記下首次付款的後五碼與核帳時間', async () => {
      const detail = await awaitOrderStatus(request, owner, orderId, ['CONFIRMED']);
      expect(detail.paymentProofLastFive).toBe('12345');
      expect(detail.paidAt).toBeTruthy();
      return { lastFive: detail.paymentProofLastFive as string, paidAt: detail.paidAt as string };
    });

    await setBrowserAuth(page, owner, 'client');
    const ownerDialogs = trackDialogs(page);

    await step('飼主從訂單詳情真實點入變更精靈，申請增加一天', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('btn-go-modification-wizard')).toBeVisible({ timeout: 10000 });
      await page.getByTestId('btn-go-modification-wizard').click();
      await expect(page).toHaveURL(new RegExp(`/orders/${orderId}/modify`));

      // OrderModificationWizard.tsx 目前用寫死的 2026-05-01~2026-05-31 當方案有效期間 mock，
      // 跟真實訂單日期無關，變更後日期只要落在這個區間就不會觸發前端的 PLAN_NOT_IN_RANGE 防呆
      await page.getByTestId('modify-dates-input').fill('2026-05-26, 2026-05-27');
      await snap(page, '變更日期填寫完成');
      await page.getByTestId('modify-submit-btn').click();
      await expect(page.getByTestId('modification-success-banner')).toBeVisible({ timeout: 10000 });
    });

    const sitterPage = await (await browser.newContext()).newPage();
    await setBrowserAuth(sitterPage, sitter, 'sitter');
    const sitterDialogs = trackDialogs(sitterPage);

    await step('保母從訂單列表真實點入報價，接受換算後的新總額（2 天）', sitterPage, async () => {
      await sitterPage.goto('/sitter/orders');
      await sitterPage.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(sitterPage.getByTestId('btn-go-modification-quote')).toBeVisible({ timeout: 10000 });
      await sitterPage.getByTestId('btn-go-modification-quote').click();
      await expect(sitterPage).toHaveURL(new RegExp(`/sitter/orders/${orderId}/quote`));

      // 表單預設值就是飼主提案換算出的新總額 (2 天 x 單價)，直接接受不用改
      await expect(sitterPage.getByTestId('quote-amount-input')).toHaveValue('1200');
      await snap(sitterPage, '報價表單');
      await sitterPage.getByTestId('quote-submit-btn').click();
      await expect(sitterPage.getByTestId('quote-success-banner')).toBeVisible({ timeout: 10000 });
    });

    await step('飼主從訂單詳情真實點入確認變更，差額為正（補款）', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('btn-go-modification-confirm')).toBeVisible({ timeout: 10000 });
      await page.getByTestId('btn-go-modification-confirm').click();
      await expect(page).toHaveURL(new RegExp(`/owner/orders/${orderId}/modification-confirm`));

      // 整段錨定：toContainText('600') 遇到退款的「$ -600」也會過，分不出補款/退款方向
      await expect(page.getByTestId('mod-confirm-diff-amount')).toHaveText(
        /^\$\s?600\s*\(負值為退款\)$/
      );
      await snap(page, '變更確認頁差額');
      await page.getByTestId('confirm-submit-btn').click();
      await expect(page.getByTestId('confirm-success-banner')).toBeVisible({ timeout: 10000 });

      await awaitOrderStatus(request, owner, orderId, ['PENDING_PAYMENT']);
      // C2 迴歸 (P7)：同意後不可再按同意；補款分支不顯示退款區塊，只剩「前往補款」
      await expect(page.getByTestId('confirm-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('mod-confirm-go-pay-btn')).toBeVisible();
      await expect(page.getByTestId('refund-confirm-section')).toHaveCount(0);
      await expect(page.getByTestId('refund-confirm-btn')).toHaveCount(0);
    });

    await step('保母重新進入報價頁（補款階段）：唯讀，無報價/拒絕/退款區塊（C2 迴歸）', sitterPage, async () => {
      await sitterPage.goto(`/sitter/orders/${orderId}/quote`);
      await expect(sitterPage.getByTestId('mod-quote-readonly-summary')).toContainText('應補款 $ 600', {
        timeout: 10000
      });
      await expect(sitterPage.getByTestId('quote-submit-btn')).toHaveCount(0);
      await expect(sitterPage.getByTestId('reject-mod-btn')).toHaveCount(0);
      await expect(sitterPage.getByTestId('refund-proof-section')).toHaveCount(0);
    });

    await step('飼主補款上傳憑證', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('input-payment-last-five')).toBeVisible({ timeout: 10000 });
      // 迴歸 (P8)：補款頁過去只顯示新總額 $1,200，飼主會整筆多匯；應補的是差額 $600
      await expect(page.getByTestId('payment-amount-due')).toHaveText('$ 600');
      await expect(page.getByTestId('order-total-amount')).toHaveText('$ 1,200');
      await expect(page.getByTestId('payment-top-up-notice')).toBeVisible();
      await page.getByTestId('input-payment-last-five').fill('54321');
      await page.getByTestId('input-payment-file').setInputFiles(path.join(__dirname, 'fixtures/kyc-selfie.jpg'));
      await page.getByTestId('checkbox-disclaimer-agreed').check();
      await snap(page, '補款表單填寫完成');
      await page.getByTestId('btn-submit-payment-proof').click();
      await expectDialog(ownerDialogs, /付款憑證提交成功/);
      await awaitOrderStatus(request, sitter, orderId, ['PAID']);
      await page.reload();
      await expect(page.getByTestId('btn-submit-payment-proof')).toHaveCount(0, { timeout: 10000 });
    });

    await step('保母核對入帳，訂單回到 CONFIRMED', sitterPage, async () => {
      await sitterPage.goto('/sitter/orders');
      await sitterPage.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(sitterPage.getByTestId('btn-verify-payment')).toBeVisible({ timeout: 10000 });
      // 迴歸 (P8)：保母核對時也要看到應收的是差額 $600，而不是預估總額 $1,200
      await expect(sitterPage.getByTestId('verify-amount-due')).toContainText('$ 600');
      // B2 迴歸：核對面板顯示的是補款憑證（54321），不是首次付款的 12345
      await expect(sitterPage.getByTestId('verify-last-five')).toHaveText('54321');
      // B1 迴歸：補款憑證預覽必須顯示，且是簽名網址而非公開路徑
      await expectSignedPaymentProof(sitterPage.getByTestId('payment-verification-panel'));
      await snap(sitterPage, '核對入帳前');
      await sitterPage.getByTestId('btn-verify-payment').click();
      await expectDialog(sitterDialogs, /入帳核對確認成功/);
      // B2 迴歸：變更是在 CONFIRMED 時發起的，核帳後回到 CONFIRMED（IN_PROGRESS 分支由
      // PaymentControllerTest 覆蓋）；首次付款的後五碼與 paidAt 不得被補款覆寫
      const afterVerify = await awaitOrderStatus(request, owner, orderId, ['CONFIRMED']);
      expect(afterVerify.paymentProofLastFive).toBe(initialPayment.lastFive);
      expect(afterVerify.paidAt).toBe(initialPayment.paidAt);
      expect(afterVerify.amountDue).toBe(1200);
      await sitterPage.reload();
      await sitterPage.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(sitterPage.getByTestId('btn-verify-payment')).toHaveCount(0, { timeout: 10000 });
    });

    await expectNegotiationPagesClosed(page, sitterPage, orderId);
  });

  test('退款分支：飼主減少天數 -> 保母報價 -> 飼主確認 -> 保母上傳退款憑證 -> 飼主確認收到退款，全流程可正常運作', async ({
    page,
    request,
    browser
  }) => {
    const { sitter, owner, orderId } = await apiStep('前置：兜出一筆兩天 CONFIRMED 訂單（減少天數才有退款可測）', async () => {
      return bootstrapConfirmedOrder(request, 'd2', 'Journey方案D2', ['2026-05-20', '2026-05-21']);
    });

    await setBrowserAuth(page, owner, 'client');
    const ownerDialogs = trackDialogs(page);

    await step('飼主從訂單詳情真實點入變更精靈，申請減少為一天', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await page.getByTestId('btn-go-modification-wizard').click();
      await expect(page).toHaveURL(new RegExp(`/orders/${orderId}/modify`));

      await page.getByTestId('modify-dates-input').fill('2026-05-20');
      await snap(page, '變更日期填寫完成');
      await page.getByTestId('modify-submit-btn').click();
      await expectDialog(ownerDialogs, /變更申請已成功提交/);
      await expect(page.getByTestId('modification-success-banner')).toBeVisible({ timeout: 10000 });
    });

    const sitterPage = await (await browser.newContext()).newPage();
    await setBrowserAuth(sitterPage, sitter, 'sitter');
    const sitterDialogs = trackDialogs(sitterPage);

    await step('保母從訂單列表真實點入報價，接受換算後的新總額（1 天）', sitterPage, async () => {
      await sitterPage.goto('/sitter/orders');
      await sitterPage.getByTestId('sitter-orders-tab-ongoing').click();
      await sitterPage.getByTestId('btn-go-modification-quote').click();
      await expect(sitterPage).toHaveURL(new RegExp(`/sitter/orders/${orderId}/quote`));

      await expect(sitterPage.getByTestId('quote-amount-input')).toHaveValue('600');
      await snap(sitterPage, '報價表單');
      await sitterPage.getByTestId('quote-submit-btn').click();
      await expectDialog(sitterDialogs, /報價已成功送出/);
      await expect(sitterPage.getByTestId('quote-success-banner')).toBeVisible({ timeout: 10000 });
      // C2 迴歸 (P7)：報價後不可再報價；飼主尚未同意前不顯示退款憑證區塊
      await expect(sitterPage.getByTestId('quote-submit-btn')).toHaveCount(0);
      await expect(sitterPage.getByTestId('refund-proof-section')).toHaveCount(0);
    });

    await step('飼主從訂單詳情真實點入確認變更，差額為負（退款）', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await page.getByTestId('btn-go-modification-confirm').click();
      await expect(page).toHaveURL(new RegExp(`/owner/orders/${orderId}/modification-confirm`));

      await expect(page.getByTestId('mod-confirm-diff-amount')).toContainText('-600');
      await snap(page, '變更確認頁差額');
      await page.getByTestId('confirm-submit-btn').click();
      await expectDialog(ownerDialogs, /變更確認成功/);
      await expect(page.getByTestId('confirm-success-banner')).toBeVisible({ timeout: 10000 });

      await awaitOrderStatus(request, owner, orderId, ['REFUND_VERIFY']);
      // C2 迴歸 (P7)：同意後不可再按同意；保母尚未上傳退款憑證前不可確認收到退款
      await expect(page.getByTestId('confirm-submit-btn')).toHaveCount(0);
      await expect(page.getByTestId('refund-awaiting-proof')).toBeVisible();
      await expect(page.getByTestId('refund-confirm-btn')).toHaveCount(0);
    });

    await step('保母重新真實點入變更協商入口，上傳退款憑證（迴歸：飼主確認後此頁過去會 404）', sitterPage, async () => {
      await sitterPage.goto('/sitter/orders');
      await sitterPage.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(sitterPage.getByTestId('btn-go-modification-quote')).toBeVisible({ timeout: 10000 });
      await sitterPage.getByTestId('btn-go-modification-quote').click();
      await expect(sitterPage).toHaveURL(new RegExp(`/sitter/orders/${orderId}/quote`));
      await expect(sitterPage.getByTestId('mod-quote-error')).not.toBeVisible();
      // C2 迴歸 (P7)：飼主已同意，報價表單與拒絕按鈕都不可再出現
      await expect(sitterPage.getByTestId('refund-proof-section')).toBeVisible({ timeout: 10000 });
      await expect(sitterPage.getByTestId('quote-submit-btn')).toHaveCount(0);
      await expect(sitterPage.getByTestId('reject-mod-btn')).toHaveCount(0);

      await sitterPage.getByTestId('refund-proof-input').fill(
        'https://storage.googleapis.com/e2e-journey-test/refund-proof.jpg'
      );
      await snap(sitterPage, '退款憑證填寫完成');
      await sitterPage.getByTestId('refund-proof-submit-btn').click();
      await expectDialog(sitterDialogs, /退款憑證已成功上傳/);
      await expect(sitterPage.getByTestId('refund-proof-success-banner')).toBeVisible({ timeout: 10000 });
      await expect(sitterPage.getByTestId('refund-proof-submit-btn')).toHaveCount(0);
    });

    await step('飼主重新真實點入變更協商入口，確認收到退款', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await page.getByTestId('btn-go-modification-confirm').click();
      await expect(page).toHaveURL(new RegExp(`/owner/orders/${orderId}/modification-confirm`));

      await snap(page, '確認收到退款前');
      await page.getByTestId('refund-confirm-btn').click();
      await expectDialog(ownerDialogs, /已確認收到退款/);
      await expect(page.getByTestId('refund-confirm-success-banner')).toBeVisible({ timeout: 10000 });
      await expect(page.getByTestId('refund-confirm-btn')).toHaveCount(0);

      await awaitOrderStatus(request, sitter, orderId, ['CONFIRMED']);
    });

    await expectNegotiationPagesClosed(page, sitterPage, orderId);
  });
});
