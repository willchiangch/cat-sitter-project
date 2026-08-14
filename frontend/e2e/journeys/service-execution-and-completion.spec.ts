import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import {
  bootstrapConfirmedOrder,
  setBrowserAuth,
  awaitOrderStatus,
  awaitNotification,
  apiAuthed
} from './helpers';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * TS-JOURNEY-03：服務執行到結案 + 通知驗證。
 * 打真後端 + 真資料庫（正式站），驗證「保母從訂單列表真實點入行程 -> Check-in -> 寫日誌 ->
 * Check-out -> 送報告 -> 飼主真實點入查看 -> 確認結案」整條鏈路，且雙方都是透過本次新補上的
 * GET /api/orders/{orderId}/visits 列表連結進入，不是繞過去直接打 URL。
 */
test.describe('Journey C: 服務執行到結案 (TS-JOURNEY-03)', () => {
  test.setTimeout(120000);

  test('保母打卡寫日誌到飼主確認結案，全流程可正常運作', async ({ page, request, browser }, testInfo) => {
    const { sitter, owner, orderId } = await test.step('前置：兜出一筆 CONFIRMED 訂單', async () => {
      return bootstrapConfirmedOrder(request, 'c', 'Journey方案C');
    });

    await setBrowserAuth(page, sitter, 'sitter');
    page.on('dialog', (dialog) => dialog.accept());

    await test.step('保母從訂單列表真實點入行程並 Check-in', async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('sitter-order-visit-link')).toBeVisible({ timeout: 10000 });
      await testInfo.attach('C-01-進行中分頁行程清單', { body: await page.screenshot(), contentType: 'image/png' });

      await page.getByTestId('sitter-order-visit-link').click();
      await expect(page.getByTestId('sitter-report-start-visit-btn')).toBeVisible();
      await page.getByTestId('sitter-report-start-visit-btn').click();
      await expect(page.getByText('已成功 Check-in')).toBeVisible({ timeout: 10000 });
      // Check-in 前查無草稿，GET /report 過去對保母分支會 404，前端 fallback 又寫死
      // visitStatus=PENDING，導致畫面卡死在待執行面板進不去編輯畫面——這是本次跟著
      // Journey C 一起修的真實 bug（見 SD-008 4.1 節），這裡直接斷言編輯面板正常切換
      await expect(page.getByTestId('sitter-report-content-input')).toBeVisible({ timeout: 10000 });
      await testInfo.attach('C-02-Check-in完成', { body: await page.screenshot(), contentType: 'image/png' });

      await awaitOrderStatus(request, sitter, orderId, ['IN_PROGRESS']);
    });

    await test.step('確認飼主收到 Check-in 通知', async () => {
      const notification = await awaitNotification(request, owner, { category: 'SERVICE_RECORD', role: 'OWNER' });
      expect(notification.category).toBe('SERVICE_RECORD');
    });

    const reportContent = `Journey C 照護日誌內容-${Date.now()}`;
    await test.step('保母撰寫日誌並上傳照片', async () => {
      await page.getByTestId('sitter-report-content-input').fill(reportContent);
      await page.getByTestId('sitter-report-save-draft-btn').click();
      await testInfo.attach('C-04-日誌草稿已存', { body: await page.screenshot(), contentType: 'image/png' });

      await page.getByTestId('sitter-report-media-file-input').setInputFiles(
        path.join(__dirname, 'fixtures/report-photo.jpg')
      );
      await page.getByTestId('sitter-report-media-caption-input').fill('現場照片');
      await page.getByTestId('sitter-report-media-upload-btn').click();
      await expect(page.getByText('多媒體檔案上傳成功')).toBeVisible({ timeout: 10000 });
      await testInfo.attach('C-04-照片上傳完成', { body: await page.screenshot(), contentType: 'image/png' });
    });

    await test.step('保母結束服務並送出報告', async () => {
      await page.getByTestId('sitter-report-end-visit-btn').click();
      await expect(page.getByTestId('sitter-report-submit-btn')).toBeVisible({ timeout: 10000 });
      await testInfo.attach('C-05-結束服務', { body: await page.screenshot(), contentType: 'image/png' });

      await page.getByTestId('sitter-report-submit-btn').click();
      await testInfo.attach('C-06-報告送出', { body: await page.screenshot(), contentType: 'image/png' });
    });

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    await setBrowserAuth(ownerPage, owner, 'client');
    ownerPage.on('dialog', (dialog) => dialog.accept());

    await test.step('飼主從訂單詳情真實點入查看日誌', async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await expect(ownerPage.getByTestId('owner-order-visit-link')).toBeVisible({ timeout: 10000 });
      await ownerPage.getByTestId('owner-order-visit-link').click();

      await expect(ownerPage.getByTestId('client-report-text-card')).toContainText(reportContent, { timeout: 10000 });
      await expect(ownerPage.getByTestId('client-report-media-card')).toBeVisible();
      await testInfo.attach('C-07-飼主看到日誌', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    await test.step('飼主確認結案', async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await expect(ownerPage.getByTestId('complete-order-btn')).toBeVisible({ timeout: 10000 });
      await ownerPage.getByTestId('complete-order-btn').click();
      await awaitOrderStatus(request, owner, orderId, ['COMPLETED']);
      await testInfo.attach('C-08-訂單結案', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    await ownerContext.close();
  });
});
