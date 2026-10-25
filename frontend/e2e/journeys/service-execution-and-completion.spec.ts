import path from 'path';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import {
  bootstrapConfirmedOrder,
  setBrowserAuth,
  awaitOrderStatus,
  awaitNotification,
  apiAuthed,
  trackOrderActionCalls,
  expectSingleTokenOnlyCall
} from './helpers';
import { step, apiStep, snap, trackDialogs, expectDialog } from '../support/evidence';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * TS-JOURNEY-03：服務執行到結案 + 通知驗證。
 * 打真後端 + 真資料庫（正式站），驗證「保母從訂單列表真實點入行程 -> Check-in -> 寫日誌 ->
 * Check-out -> 送報告 -> 飼主真實點入查看 -> 確認結案」整條鏈路，且雙方都是透過本次新補上的
 * GET /api/orders/{orderId}/visits 列表連結進入，不是繞過去直接打 URL。
 */
test.describe('Journey C: 服務執行到結案 (TS-JOURNEY-03)', () => {
  test.setTimeout(120000);

  test('保母打卡寫日誌到飼主確認結案，全流程可正常運作', async ({ page, request, browser }) => {
    const { sitter, owner, orderId } = await apiStep('前置：兜出一筆 CONFIRMED 訂單', async () => {
      return bootstrapConfirmedOrder(request, 'c', 'Journey方案C');
    });

    await setBrowserAuth(page, sitter, 'sitter');
    trackDialogs(page);

    await step('C-02 保母從訂單列表真實點入行程並 Check-in', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('sitter-order-visit-link')).toBeVisible({ timeout: 10000 });
      await snap(page, 'C-01 進行中分頁行程清單');

      await page.getByTestId('sitter-order-visit-link').click();
      await expect(page.getByTestId('sitter-report-start-visit-btn')).toBeVisible();
      await snap(page, 'C-02 Check-in 前待執行面板');
      await page.getByTestId('sitter-report-start-visit-btn').click();
      await expect(page.getByText('已成功 Check-in')).toBeVisible({ timeout: 10000 });
      // Check-in 前查無草稿，GET /report 過去對保母分支會 404，前端 fallback 又寫死
      // visitStatus=PENDING，導致畫面卡死在待執行面板進不去編輯畫面——這是本次跟著
      // Journey C 一起修的真實 bug（見 SD-008 4.1 節），這裡直接斷言編輯面板正常切換
      await expect(page.getByTestId('sitter-report-content-input')).toBeVisible({ timeout: 10000 });

      await awaitOrderStatus(request, sitter, orderId, ['IN_PROGRESS']);
    });

    await apiStep('C-03 確認飼主收到 Check-in 通知', async () => {
      const notification = await awaitNotification(request, owner, {
        category: 'SERVICE_RECORD',
        role: 'OWNER'
      });
      expect(notification.category).toBe('SERVICE_RECORD');
      return notification;
    });

    const reportContent = `Journey C 照護日誌內容-${Date.now()}`;
    await step('C-04 保母撰寫日誌並上傳照片', page, async () => {
      await page.getByTestId('sitter-report-content-input').fill(reportContent);
      await page.getByTestId('sitter-report-save-draft-btn').click();
      // 舊版 click 完直接截圖，截到的是「儲存中...」或尚未出現成功訊息的畫面
      await expect(page.getByText('暫存草稿儲存成功')).toBeVisible({ timeout: 10000 });
      await snap(page, 'C-04 日誌草稿已存');

      await page
        .getByTestId('sitter-report-media-file-input')
        .setInputFiles(path.join(__dirname, 'fixtures/report-photo.jpg'));
      await page.getByTestId('sitter-report-media-caption-input').fill('現場照片');
      await snap(page, 'C-04 照片與說明填寫完成');
      await page.getByTestId('sitter-report-media-upload-btn').click();
      await expect(page.getByText('多媒體檔案上傳成功')).toBeVisible({ timeout: 10000 });
      // 成功訊息先出現、GET /report 重抓後清單才更新；舊版截到「照護媒體清單 (0 筆)」
      await expect(page.getByText('照護媒體清單 (1 筆)')).toBeVisible();
    });

    await step('C-06 保母結束服務並送出報告', page, async () => {
      await page.getByTestId('sitter-report-end-visit-btn').click();
      await expect(page.getByTestId('sitter-report-submit-btn')).toBeVisible({ timeout: 10000 });
      await snap(page, 'C-05 結束服務');

      await page.getByTestId('sitter-report-submit-btn').click();
      // 舊版 click 完直接截圖，截到的是送出前的畫面
      await expect(page.getByText('照護日誌已成功送出！')).toBeVisible({ timeout: 10000 });
      await expect(page.getByText('已完工 (已送出)')).toBeVisible();
    });

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    await setBrowserAuth(ownerPage, owner, 'client');
    const ownerDialogs = trackDialogs(ownerPage);

    await step('C-07 飼主從訂單詳情真實點入查看日誌', ownerPage, async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await expect(ownerPage.getByTestId('owner-order-visit-link')).toBeVisible({ timeout: 10000 });
      await ownerPage.getByTestId('owner-order-visit-link').click();

      await expect(ownerPage.getByTestId('client-report-text-card')).toContainText(reportContent, {
        timeout: 10000
      });
      await expect(ownerPage.getByTestId('client-report-media-card')).toBeVisible();
    });

    await step('C-08 飼主確認結案', ownerPage, async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await expect(ownerPage.getByTestId('complete-order-btn')).toBeVisible({ timeout: 10000 });
      // U5（驗收 P6）：徽章過去直接顯示英文 IN_PROGRESS
      await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('服務進行中');
      await snap(ownerPage, 'C-08 結案前訂單詳情');
      await ownerPage.getByTestId('complete-order-btn').click();
      await expectDialog(ownerDialogs, /訂單已順利結案/);
      await awaitOrderStatus(request, owner, orderId, ['COMPLETED']);
      // 結案後頁面不一定重抓訂單，reload 後才是真正的 COMPLETED 畫面
      await ownerPage.reload();
      await expect(ownerPage.getByTestId('completed-status-banner')).toBeVisible({
        timeout: 10000
      });
      await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('已結案');
    });

    await ownerContext.close();
  });

  /**
   * fix-batch12 J6：第 11 批把 POST /api/orders/{id}/dispute 的飼主身分改取自 token（BOLA），
   * 但真後端 journey 過去從未呼叫這支（CI run 40177738227：dispute 0 次）。保母以 API 完成打卡 / 結束服務
   * （UI 打卡在上一條已覆蓋），飼主從訂單詳情 UI 申報爭議，斷言請求不帶 ownerId、DISPUTED、結案鈕消失。
   */
  test('服務完成後飼主申報爭議：訂單轉爭議處理中，結案鈕消失，請求不帶身分參數', async ({
    request,
    browser
  }) => {
    const { sitter, owner, orderId } = await apiStep('前置：兜出一筆 CONFIRMED 訂單', async () => {
      return bootstrapConfirmedOrder(request, 'cd', 'Journey爭議方案');
    });

    await apiStep(
      '前置：保母以 API Check-in / Check-out，行程全部 DONE、訂單 IN_PROGRESS',
      async () => {
        const visitsRes = await request.get(`/api/orders/${orderId}/visits`, {
          headers: apiAuthed(sitter)
        });
        expect(visitsRes.ok()).toBeTruthy();
        const visits: Array<{ id: string }> = await visitsRes.json();
        expect(visits.length).toBeGreaterThan(0);
        for (const visit of visits) {
          for (const action of ['start', 'end']) {
            const res = await request.post(`/api/visits/${visit.id}/${action}`, {
              headers: { ...apiAuthed(sitter), 'Idempotency-Key': randomUUID() }
            });
            expect(res.ok(), `${action} ${visit.id}: ${res.status()}`).toBeTruthy();
          }
        }
        const after = await request.get(`/api/orders/${orderId}/visits`, {
          headers: apiAuthed(owner)
        });
        const afterVisits: Array<{ status: string }> = await after.json();
        expect(afterVisits.map((v) => v.status)).toEqual(afterVisits.map(() => 'DONE'));
        const order = await awaitOrderStatus(request, owner, orderId, ['IN_PROGRESS']);
        return { visits: afterVisits, status: order.status };
      }
    );

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    await setBrowserAuth(ownerPage, owner, 'client');
    const ownerDialogs = trackDialogs(ownerPage);
    const disputeCalls = trackOrderActionCalls(ownerPage, orderId, 'dispute');

    await step('CD-01 飼主訂單詳情：服務進行中，結案鈕與申報爭議都在', ownerPage, async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('服務進行中');
      await expect(ownerPage.getByTestId('complete-order-btn')).toBeVisible({ timeout: 10000 });
      await expect(ownerPage.getByTestId('dispute-order-btn')).toBeVisible();
    });

    await step('CD-02 開申報爭議視窗並填寫描述', ownerPage, async () => {
      await ownerPage.getByTestId('dispute-order-btn').click();
      await expect(ownerPage.getByTestId('dispute-modal')).toBeVisible();
      await ownerPage
        .getByTestId('dispute-desc-textarea')
        .fill(`Journey C 爭議：照片與實際不符-${Date.now()}`);
      await expect(ownerPage.getByTestId('dispute-submit-btn')).toBeEnabled();
    });

    await step(
      'CD-03 送出爭議：alert 成功，徽章「爭議處理中」、爭議橫幅出現，結案與申報爭議鈕消失',
      ownerPage,
      async () => {
        await ownerPage.getByTestId('dispute-submit-btn').click();
        await expectDialog(ownerDialogs, /爭議申報成功/);
        await expect(ownerPage.getByTestId('dispute-modal')).toHaveCount(0);
        await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('爭議處理中');
        await expect(ownerPage.getByTestId('disputed-status-banner')).toBeVisible();
        await expect(ownerPage.getByTestId('complete-order-btn')).toHaveCount(0);
        await expect(ownerPage.getByTestId('dispute-order-btn')).toHaveCount(0);
      }
    );

    await apiStep(
      'CD-04 爭議請求：POST /api/orders/{id}/dispute 恰好一次、不帶 ownerId / sitterId；訂單 DISPUTED',
      async () => {
        expectSingleTokenOnlyCall(disputeCalls, 'dispute');
        const order = await awaitOrderStatus(request, owner, orderId, ['DISPUTED']);
        return { disputeCalls, status: order.status };
      }
    );

    await ownerContext.close();
  });
});
