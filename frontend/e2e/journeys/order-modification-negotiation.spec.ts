import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { bootstrapConfirmedOrder, setBrowserAuth, awaitOrderStatus } from './helpers';
import { step, apiStep, snap, trackDialogs, expectDialog } from '../support/evidence';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

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

      await expect(page.getByTestId('mod-confirm-diff-amount')).toContainText('600');
      await snap(page, '變更確認頁差額');
      await page.getByTestId('confirm-submit-btn').click();
      await expect(page.getByTestId('confirm-success-banner')).toBeVisible({ timeout: 10000 });

      await awaitOrderStatus(request, owner, orderId, ['PENDING_PAYMENT']);
    });

    await step('飼主補款上傳憑證', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await expect(page.getByTestId('input-payment-last-five')).toBeVisible({ timeout: 10000 });
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
      await snap(sitterPage, '核對入帳前');
      await sitterPage.getByTestId('btn-verify-payment').click();
      await expectDialog(sitterDialogs, /入帳核對確認成功/);
      await awaitOrderStatus(request, owner, orderId, ['CONFIRMED']);
      await sitterPage.reload();
      await sitterPage.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(sitterPage.getByTestId('btn-verify-payment')).toHaveCount(0, { timeout: 10000 });
    });
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
    });

    await step('保母重新真實點入變更協商入口，上傳退款憑證（迴歸：飼主確認後此頁過去會 404）', sitterPage, async () => {
      await sitterPage.goto('/sitter/orders');
      await sitterPage.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(sitterPage.getByTestId('btn-go-modification-quote')).toBeVisible({ timeout: 10000 });
      await sitterPage.getByTestId('btn-go-modification-quote').click();
      await expect(sitterPage).toHaveURL(new RegExp(`/sitter/orders/${orderId}/quote`));
      await expect(sitterPage.getByTestId('mod-quote-error')).not.toBeVisible();

      await sitterPage.getByTestId('refund-proof-input').fill(
        'https://storage.googleapis.com/e2e-journey-test/refund-proof.jpg'
      );
      await snap(sitterPage, '退款憑證填寫完成');
      await sitterPage.getByTestId('refund-proof-submit-btn').click();
      await expectDialog(sitterDialogs, /退款憑證已成功上傳/);
      await expect(sitterPage.getByTestId('refund-proof-success-banner')).toBeVisible({ timeout: 10000 });
    });

    await step('飼主重新真實點入變更協商入口，確認收到退款', page, async () => {
      await page.goto(`/owner/orders/${orderId}`);
      await page.getByTestId('btn-go-modification-confirm').click();
      await expect(page).toHaveURL(new RegExp(`/owner/orders/${orderId}/modification-confirm`));

      await snap(page, '確認收到退款前');
      await page.getByTestId('refund-confirm-btn').click();
      await expectDialog(ownerDialogs, /已確認收到退款/);
      await expect(page.getByTestId('refund-confirm-success-banner')).toBeVisible({ timeout: 10000 });

      await awaitOrderStatus(request, sitter, orderId, ['CONFIRMED']);
    });
  });
});
