import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect, type Page } from '@playwright/test';
import {
  provisionAndLogin,
  setBrowserAuth,
  bootstrapVerifiedSitter,
  bootstrapPendingOrder,
  setSitterPlanTier,
  awaitOrderStatus,
  awaitNotification,
  pollUntil,
  apiAuthed,
  expectSignedPaymentProof,
  trackOrderActionCalls,
  expectSingleTokenOnlyCall
} from './helpers';
import { step, apiStep, snap, trackDialogs, expectDialog } from '../support/evidence';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** 比照既有 client-booking.spec.ts 的日曆操作邏輯（真後端版本不需要跨月，簡化成單一日期） */
async function selectFirstAvailableDate(page: Page, planIdx: number, scheduleIdx: number) {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const dateStr = tomorrow.toISOString().split('T')[0];
  const selector = `[data-testid="client-booking-date-${planIdx}-${scheduleIdx}-${dateStr}"]`;
  await page.waitForSelector(selector);
  await page.click(selector);
  await page.click(`[data-testid="client-booking-btn-confirm-date-${planIdx}-${scheduleIdx}"]`);
}

/**
 * TS-JOURNEY-02：飼主下單到付款全流程。
 * 打真後端 + 真資料庫（正式站），驗證「未登入點立即預約 -> redirect 登入 -> 建寵物 -> 下單 ->
 * 保母接單 -> 飼主付款 -> 保母核對入帳 -> 記事本互動」這條跨模組交易鏈路真的打得通。
 */
test.describe('Journey B: 飼主下單到付款全流程 (TS-JOURNEY-02)', () => {
  test.setTimeout(120000);

  test('飼主從公開頁下單到保母確認入帳，全流程可正常運作', async ({ page, request, browser }) => {
    const sitter = await apiStep('前置：兜出一個 VERIFIED+isOpen+有方案的保母', async () => {
      return bootstrapVerifiedSitter(request, 'b', 'Journey方案B');
    });

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    const ownerDialogs = trackDialogs(ownerPage);
    const bookingPath = `/booking/${sitter.userId}`;

    await step(
      'B-01 未登入訪客在公開頁點立即預約，導向註冊頁並帶 redirect',
      ownerPage,
      async () => {
        await ownerPage.goto(`/sitter/${sitter.userId}/profile`);
        await expect(ownerPage.getByTestId('public-profile-book-now-btn')).toBeVisible();
        await snap(ownerPage, 'B-01 保母公開頁');
        await ownerPage.getByTestId('public-profile-book-now-btn').click();
        await expect(ownerPage).toHaveURL(
          new RegExp(
            `/register\\?redirect=${encodeURIComponent(bookingPath).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`
          )
        );
      }
    );

    const owner = await apiStep('API 建立飼主帳號（略過 OTP）', () =>
      provisionAndLogin(request, 'b', 'OWNER', 'Journey飼主B')
    );

    await step('B-02 改走登入並驗證 redirect 導回下單頁', ownerPage, async () => {
      await ownerPage.getByRole('link', { name: '已經有帳號了？返回登入' }).click();
      await expect(ownerPage).toHaveURL(new RegExp(`/login\\?redirect=`));
      await ownerPage.getByTestId('login-email-input').fill(owner.email);
      await ownerPage.getByTestId('login-password-input').fill(owner.password);
      await snap(ownerPage, 'B-02 登入表單填寫完成');
      await ownerPage.getByTestId('login-submit-btn').click();
      await expect(ownerPage).toHaveURL(
        new RegExp(bookingPath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
        { timeout: 15000 }
      );
      await expect(ownerPage.getByRole('heading', { name: '選擇服務方案' })).toBeVisible();
    });

    await step('B-03 飼主建立寵物資料並上傳頭像', ownerPage, async () => {
      await ownerPage.goto('/pets');
      await ownerPage.getByTestId('btn-add-pet').click();
      await ownerPage.getByTestId('input-pet-name').fill('Journey測試貓B');
      await ownerPage
        .getByTestId('avatar-file-input')
        .setInputFiles(path.join(__dirname, 'fixtures/pet-avatar.jpg'));
      await snap(ownerPage, 'B-03 寵物表單填寫完成');
      await ownerPage.getByTestId('btn-save-pet').click();
      // 存檔後 PetManager 會自動切到該寵物的編輯面板，畫面上同時有「寵物卡片」與
      // 「編輯 XXX 的基本資料」兩處都含寵物名稱文字，用寬鬆的 getByText 會撞 strict mode
      await expect(
        ownerPage.getByTestId('pet-card').getByRole('heading', { name: 'Journey測試貓B' })
      ).toBeVisible({ timeout: 10000 });
      // 頭像是建檔後才另外 POST /api/pets/{id}/avatar，舊版只等到寵物卡片出現就截圖，
      // 截到的還是「J」佔位字，下一步 goto 又把上傳 request abort 掉。要等卡片真的換成圖片且已載入
      const avatarImg = ownerPage
        .getByTestId('pet-card')
        .filter({ has: ownerPage.getByRole('heading', { name: 'Journey測試貓B' }) })
        .getByRole('img', { name: 'Journey測試貓B' });
      await expect(avatarImg).toBeVisible({ timeout: 15000 });
      await expect
        .poll(() => avatarImg.evaluate((img) => (img as HTMLImageElement).naturalWidth), {
          timeout: 10000
        })
        .toBeGreaterThan(0);
    });

    await step('B-04 飼主完成預約送單', ownerPage, async () => {
      await ownerPage.goto(bookingPath);
      await expect(ownerPage.getByRole('heading', { name: '選擇服務方案' })).toBeVisible();
      await ownerPage.click('[data-testid="client-booking-plan-card-0"]');
      await expect(ownerPage.getByRole('heading', { name: '排程配置' })).toBeVisible();
      await snap(ownerPage, 'B-04 選擇方案後排程配置');
      await selectFirstAvailableDate(ownerPage, 0, 0);
      await snap(ownerPage, 'B-04 選擇日期完成');
      await ownerPage.click('[data-testid="client-booking-btn-step1-next"]');
      await expect(ownerPage.getByRole('heading', { name: '預約摘要' })).toBeVisible();
      await snap(ownerPage, 'B-04 預約摘要');
      await ownerPage.click('[data-testid="client-booking-btn-submit"]');
      // 送出成功後 PublicBookingPage 會 navigate 到 /owner/orders/{orderId}，用這個導航
      // 當作「真的送出成功」的訊號，不能只 await click() 就當作完成——click() 只保證事件
      // 有派發，不保證背後的非同步送單/導航已經跑完
      await expect(ownerPage).toHaveURL(/\/owner\/orders\/[0-9a-f-]+/, { timeout: 15000 });
    });

    const orderId = ownerPage.url().match(/\/owner\/orders\/([0-9a-f-]+)/)?.[1];
    if (!orderId) throw new Error(`無法從網址解析 orderId: ${ownerPage.url()}`);

    // 實測發現：正式站極短時間內（約 1 秒內）用不同身份查詢剛寫入的訂單有機率撲空
    // （見 awaitOrderStatus 註解），下單後不能立刻切換身份查列表頁，要先 poll 確認資料已可讀
    await apiStep('確認訂單已可被查詢到 (PENDING)', async () => {
      return awaitOrderStatus(request, owner, orderId, ['PENDING']);
    });

    await setBrowserAuth(page, sitter, 'sitter');
    const sitterDialogs = trackDialogs(page);

    await step('B-05 保母原價直接接受訂單', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('btn-go-order-eval').click();
      await expect(page.getByTestId('sitter-order-eval-btn-accept-original')).toBeVisible();
      await snap(page, 'B-05 訂單評估頁');
      await page.getByTestId('sitter-order-eval-btn-accept-original').click();
      await expectDialog(sitterDialogs, /已成功接單/);
      await expect(page).toHaveURL(/\/sitter\/orders/, { timeout: 15000 });
      await awaitOrderStatus(request, sitter, orderId, ['PENDING_PAYMENT']);
      // X1 迴歸：不 reload，直接在導回的列表切到進行中，要看到「待付款」。
      // 過去 OrderEvalView 接單後沒 invalidate 訂單快取，列表 60 秒內仍顯示「評估中 / 待評估」
      await expect(page.getByTestId('btn-go-order-eval')).toHaveCount(0);
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByText('待付款', { exact: true })).toBeVisible();
    });

    await step('B-06 飼主上傳線下付款憑證', ownerPage, async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await ownerPage.getByTestId('input-payment-last-five').fill('12345');
      await ownerPage
        .getByTestId('input-payment-file')
        .setInputFiles(path.join(__dirname, 'fixtures/kyc-selfie.jpg'));
      await ownerPage.getByTestId('checkbox-disclaimer-agreed').check();
      await snap(ownerPage, 'B-06 付款表單填寫完成');
      await ownerPage.getByTestId('btn-submit-payment-proof').click();
      await expectDialog(ownerDialogs, /付款憑證提交成功/);
      await awaitOrderStatus(request, owner, orderId, ['PAID']);
      // 舊版 API poll 到 PAID 就直接截圖，畫面可能還停在付款表單；reload 後斷言 PAID 畫面再截
      await ownerPage.reload();
      await expect(ownerPage.getByTestId('btn-submit-payment-proof')).toHaveCount(0, {
        timeout: 10000
      });
      // B1 迴歸：飼主看自己的憑證也要走簽名網址
      await expectSignedPaymentProof(ownerPage, '飼主端付款憑證預覽為簽名網址，不帶簽名 403');
    });

    await step('B-07 保母核對入帳', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('btn-verify-payment')).toBeVisible({ timeout: 10000 });
      // B1 迴歸：保母核對面板的憑證預覽必須顯示，且是簽名網址而非公開路徑
      await expect(page.getByTestId('verify-last-five')).toHaveText('12345');
      await expectSignedPaymentProof(
        page.getByTestId('payment-verification-panel'),
        '保母核對面板付款憑證預覽為簽名網址，不帶簽名 403'
      );
      // U8（驗收 N3）：駁回鈕不得再是金色漸層底配灰棕字（對比度驗算見 payment-reject-button-contrast.spec.ts）
      await expect(page.getByTestId('btn-reject-payment')).toHaveCSS('background-image', 'none');
      await expect(page.getByTestId('btn-reject-payment')).toHaveCSS('color', 'rgb(176, 37, 0)');
      await snap(page, 'B-07 核對入帳前');
      await page.getByTestId('btn-verify-payment').click();
      await expectDialog(sitterDialogs, /入帳核對確認成功/);
      await awaitOrderStatus(request, sitter, orderId, ['CONFIRMED']);
      // API 已 CONFIRMED 不代表列表已重抓（正式站有讀寫延遲），reload 後再斷言核對按鈕消失
      await page.reload();
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('btn-verify-payment')).toHaveCount(0, { timeout: 10000 });
    });

    await step('B-08 雙方在照護記事本互動', ownerPage, async () => {
      const careNoteContent = `Journey B 記事本測試內容-${Date.now()}`;
      await page.goto(`/care-notes/manage/${sitter.userId}/${owner.userId}`);
      await page.getByTestId('sitter-carenote-add-item-OTHER').click();
      await page.getByTestId('sitter-carenote-item-title').fill('注意事項');
      await page.getByTestId('sitter-carenote-item-content').fill(careNoteContent);
      await page.getByTestId('sitter-carenote-save-btn').click();
      // 舊版 click 完直接截圖，儲存成功與否完全看不出來（成功/失敗都是 alert）
      await expectDialog(sitterDialogs, /記事本儲存成功/);
      // 新增的條目要真的在畫面上（截圖靠 evidence.ts 的 fullPage 解除 AppShell 裁切才拍得到）
      await expect
        .poll(() =>
          page
            .getByTestId('sitter-carenote-item-content')
            .evaluateAll((els) => els.map((el) => (el as HTMLTextAreaElement).value))
        )
        .toContain(careNoteContent);
      await snap(page, 'B-08 保母寫入記事本');

      // 同 awaitOrderStatus 的教訓：保母存檔後，飼主緊接著查詢有機率撲空（見 pollUntil 註解），
      // 先用 API poll 確認資料真的可讀了，再切去 UI 驗證顯示
      await pollUntil(
        async () => {
          const res = await request.get(`/api/care-notes/${sitter.userId}/${owner.userId}`, {
            headers: apiAuthed(owner)
          });
          return res.ok() ? res.json() : null;
        },
        (body) => JSON.stringify(body).includes(careNoteContent)
      );

      await ownerPage.goto(`/care-notes/view/${sitter.userId}/${owner.userId}`);
      await expect(ownerPage.getByText(careNoteContent)).toBeVisible({ timeout: 10000 });
    });

    await ownerContext.close();
  });

  /**
   * 議價分支（驗收 R7）：保母不走原價快速通道，改在評估頁「報價微調」加價 + 折扣並填原因，
   * 走 EvaluationService.sendQuote（第 7 批加了 FOR UPDATE 列鎖，過去只有 JUnit 覆蓋）。
   * 前段（上架、下單）主流程已驗過 UI，這裡用 API 兜出 PENDING 訂單，從報價開始走 UI。
   */
  test('保母調整報價（加價 + 折扣 + 原因）後飼主依調整後金額付款，保母核對入帳成立', async ({
    page,
    request,
    browser
  }) => {
    const ADD_FEE = 300;
    const DISCOUNT = 100;
    const ORIGINAL_TOTAL = 600;
    const QUOTED_TOTAL = ORIGINAL_TOTAL + ADD_FEE - DISCOUNT;
    const reason = `Journey B 議價：距離較遠加收車馬費、老客戶折扣-${Date.now()}`;

    const { sitter, owner, orderId } = await apiStep(
      '前置：API 兜出 VERIFIED 保母 + 飼主 + PENDING 訂單（$600）',
      async () => {
        const ctx = await bootstrapPendingOrder(request, 'bq', 'Journey議價方案');
        const order = await awaitOrderStatus(request, ctx.owner, ctx.orderId, ['PENDING']);
        expect(order.totalAmount).toBe(ORIGINAL_TOTAL);
        return { ...ctx, status: order.status, totalAmount: order.totalAmount };
      }
    );

    await apiStep('前置：管理員把保母訂閱設為 PRO（調價需要專業方案以上）', async () => {
      const sub = await setSitterPlanTier(request, sitter.userId, 'PRO');
      expect(sub.planTier).toBe('PRO');
      return { sitterId: sitter.userId, ...sub };
    });

    await setBrowserAuth(page, sitter, 'sitter');
    const sitterDialogs = trackDialogs(page);

    await step('B2-01 保母開啟訂單評估頁：原始下單金額 $600', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('btn-go-order-eval').click();
      await expect(page).toHaveURL(new RegExp(`/sitter/eval/${orderId}$`));
      await expect(page.getByTestId('sitter-order-eval-total')).toHaveText('$ 600');
      await expect(page.getByTestId('sitter-order-eval-btn-accept-original')).toBeVisible();
      await expect(page.getByTestId('sitter-order-eval-btn-confirm')).toHaveCount(0);
    });

    await step('B2-02 加價 300、折扣 100、填寫調整原因：報價總計 $800', page, async () => {
      await page.getByTestId('sitter-order-eval-input-add-fee').fill(String(ADD_FEE));
      await page.getByTestId('sitter-order-eval-input-discount').fill(String(DISCOUNT));
      await expect(page.getByTestId('sitter-order-eval-input-reason')).toBeVisible();
      await page.getByTestId('sitter-order-eval-input-reason').fill(reason);
      await expect(page.getByTestId('sitter-order-eval-total')).toHaveText('$ 800');
      // 有調整金額時原價快速通道消失，只剩「發送調整後報價」
      await expect(page.getByTestId('sitter-order-eval-btn-accept-original')).toHaveCount(0);
      await expect(page.getByTestId('sitter-order-eval-btn-confirm')).toHaveText('發送調整後報價');
      await page.getByTestId('sitter-order-eval-btn-confirm').scrollIntoViewIfNeeded();
      await expect(page.getByTestId('sitter-order-eval-total')).toBeInViewport();
    });

    await step('B2-03 送出報價：導回列表，「進行中」顯示待付款 $800', page, async () => {
      await page.getByTestId('sitter-order-eval-btn-confirm').click();
      await expectDialog(sitterDialogs, /報價已成功送出/);
      await expect(page).toHaveURL(/\/sitter\/orders$/, { timeout: 15000 });
      await awaitOrderStatus(request, sitter, orderId, ['PENDING_PAYMENT']);
      // 不 reload：OrderEvalView 送報價後 invalidate 訂單快取，列表要直接反映新狀態與金額
      await expect(page.getByTestId('btn-go-order-eval')).toHaveCount(0);
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByText('待付款', { exact: true })).toBeVisible();
      await expect(page.getByTestId(`sitter-order-total-${orderId}`)).toHaveText('$ 800');
    });

    await apiStep(
      '報價後訂單：PENDING_PAYMENT、totalAmount 800、adjustmentAmount 200、原因與應付金額一致',
      async () => {
        const order = await awaitOrderStatus(request, owner, orderId, ['PENDING_PAYMENT']);
        expect(order.totalAmount).toBe(QUOTED_TOTAL);
        expect(order.adjustmentAmount).toBe(ADD_FEE - DISCOUNT);
        expect(order.adjustmentReason).toBe(reason);
        expect(order.amountDue).toBe(QUOTED_TOTAL);
        return {
          status: order.status,
          totalAmount: order.totalAmount,
          adjustmentAmount: order.adjustmentAmount,
          adjustmentReason: order.adjustmentReason,
          amountDue: order.amountDue,
          version: order.version
        };
      }
    );

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    const ownerDialogs = trackDialogs(ownerPage);
    await setBrowserAuth(ownerPage, owner, 'client');

    await step(
      'B2-04 飼主付款頁顯示調整後金額 $800（非補款差額）與調整原因',
      ownerPage,
      async () => {
        await ownerPage.goto(`/owner/orders/${orderId}`);
        await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('待付款');
        await expect(ownerPage.getByTestId('order-total-amount')).toHaveText('$ 800');
        await expect(ownerPage.getByTestId('payment-top-up-notice')).toHaveCount(0);
        // fix-batch12 U6（第 9 批另外發現 #4）：保母填的調整原因過去飼主端看不到
        await expect(ownerPage.getByTestId('order-adjustment-amount')).toHaveText('加價 $ 200');
        await expect(ownerPage.getByTestId('order-adjustment-reason')).toHaveText(
          `調整原因：${reason}`
        );
        await expect(ownerPage.getByTestId('btn-submit-payment-proof')).toBeVisible();
      }
    );

    await step('B2-05 飼主上傳付款憑證：已付款', ownerPage, async () => {
      await ownerPage.getByTestId('input-payment-last-five').fill('24680');
      await ownerPage
        .getByTestId('input-payment-file')
        .setInputFiles(path.join(__dirname, 'fixtures/kyc-selfie.jpg'));
      await ownerPage.getByTestId('checkbox-disclaimer-agreed').check();
      await expect(ownerPage.getByTestId('checkbox-disclaimer-agreed')).toBeChecked();
      await snap(ownerPage, 'B2-05 付款表單填寫完成（$800）');
      await ownerPage.getByTestId('btn-submit-payment-proof').click();
      await expectDialog(ownerDialogs, /付款憑證提交成功/);
      await awaitOrderStatus(request, owner, orderId, ['PAID']);
      await ownerPage.reload();
      await expect(ownerPage.getByTestId('btn-submit-payment-proof')).toHaveCount(0, {
        timeout: 10000
      });
      await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('已付款');
      await expect(ownerPage.getByTestId('order-total-amount')).toHaveText('$ 800');
    });

    await step('B2-06 保母核對入帳：金額 $800、後五碼 24680，確認後成立', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('btn-verify-payment')).toBeVisible({ timeout: 10000 });
      await expect(page.getByTestId('verify-last-five')).toHaveText('24680');
      await expect(page.getByTestId(`sitter-order-total-${orderId}`)).toHaveText('$ 800');
      // 首次付款：應收 = 報價後總額，不顯示「補款差額」列
      await expect(page.getByTestId('verify-amount-due')).toHaveCount(0);
      await snap(page, 'B2-06 核對入帳前（$800）');
      await page.getByTestId('btn-verify-payment').click();
      await expectDialog(sitterDialogs, /入帳核對確認成功/);
      await awaitOrderStatus(request, sitter, orderId, ['CONFIRMED']);
      await page.reload();
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('btn-verify-payment')).toHaveCount(0, { timeout: 10000 });
      await expect(page.getByTestId(`sitter-order-total-${orderId}`)).toHaveText('$ 800');
    });

    await apiStep('成立後訂單：CONFIRMED、金額維持報價後 $800', async () => {
      const order = await awaitOrderStatus(request, owner, orderId, ['CONFIRMED']);
      expect(order.totalAmount).toBe(QUOTED_TOTAL);
      expect(order.adjustmentAmount).toBe(ADD_FEE - DISCOUNT);
      expect(order.paymentProofLastFive).toBe('24680');
      return {
        status: order.status,
        totalAmount: order.totalAmount,
        adjustmentAmount: order.adjustmentAmount,
        paymentProofLastFive: order.paymentProofLastFive,
        paidAt: order.paidAt
      };
    });

    await ownerContext.close();
  });

  /**
   * 撤單分支（fix-batch10 H5，PRD-006 例外處理「飼主在保母審核中嘗試取消」）：
   * 保母評估中（PENDING）飼主在詳情頁取消預約 → 訂單 CANCELLED、cancelledBy OWNER，保母收到 ORDER_AFFAIR 通知，
   * 保母端歷史訂單顯示「飼主已取消預約」、評估頁沒有接單按鈕，保母直接打接單 API 也是 409。
   */
  test('保母評估中飼主取消預約：保母端顯示「飼主已取消預約」且無法接單', async ({
    page,
    request,
    browser
  }) => {
    const { sitter, owner, orderId } = await apiStep(
      '前置：API 兜出 VERIFIED 保母 + 飼主 + PENDING 訂單（$600）',
      async () => {
        const ctx = await bootstrapPendingOrder(request, 'bc', 'Journey撤單方案');
        const order = await awaitOrderStatus(request, ctx.owner, ctx.orderId, ['PENDING']);
        expect(order.cancellableByOwner).toBe(true);
        return { ...ctx, status: order.status, cancellableByOwner: order.cancellableByOwner };
      }
    );

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    const ownerDialogs = trackDialogs(ownerPage);
    await setBrowserAuth(ownerPage, owner, 'client');

    await step('BC-01 飼主開訂單詳情：保母評估中，顯示「取消預約」', ownerPage, async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('待評估');
      await expect(ownerPage.getByTestId('owner-cancel-booking-btn')).toHaveText('取消預約');
    });

    await step('BC-02 確認取消：訂單已取消、顯示「您已取消此預約」', ownerPage, async () => {
      await ownerPage.getByTestId('owner-cancel-booking-btn').click();
      await expectDialog(ownerDialogs, /確定要取消這筆預約嗎？/);
      await expectDialog(ownerDialogs, /^已取消預約$/);
      await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('已取消');
      await expect(ownerPage.getByTestId('cancelled-status-banner')).toHaveText('您已取消此預約');
      await expect(ownerPage.getByTestId('owner-cancel-booking-btn')).toHaveCount(0);
    });

    await apiStep('撤單後訂單：CANCELLED、cancelledBy OWNER、不可再撤', async () => {
      const order = await awaitOrderStatus(request, owner, orderId, ['CANCELLED']);
      expect(order.cancelledBy).toBe('OWNER');
      expect(order.cancellableByOwner).toBe(false);
      return {
        status: order.status,
        cancelledBy: order.cancelledBy,
        cancellableByOwner: order.cancellableByOwner
      };
    });

    await apiStep('保母收到「飼主已取消預約」通知（ORDER_AFFAIR）', async () => {
      const n = await awaitNotification(request, sitter, {
        category: 'ORDER_AFFAIR',
        role: 'SITTER',
        predicate: (x: any) => x.title === '飼主已取消預約'
      });
      expect(n.linkUrl).toBe('/sitter/orders');
      return { title: n.title, category: n.category, linkUrl: n.linkUrl };
    });

    await apiStep('保母直接打原價接單 API：409，訂單仍是 CANCELLED', async () => {
      const res = await request.post(`/api/orders/${orderId}/confirm`, {
        headers: apiAuthed(sitter)
      });
      expect(res.status()).toBe(409);
      const order = await awaitOrderStatus(request, owner, orderId, ['CANCELLED']);
      return { confirmStatus: res.status(), orderStatus: order.status };
    });

    await setBrowserAuth(page, sitter, 'sitter');

    await step('BC-03 保母歷史訂單：顯示「飼主已取消預約」', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-history').click();
      await expect(page.getByTestId(`sitter-order-owner-cancelled-${orderId}`)).toHaveText(
        '飼主已取消預約'
      );
    });

    // 驗收 A4：過去 goto 後立刻斷言 btn-go-order-eval 數量 0，列表還沒載入就會通過（空轉）。
    // 先在歷史訂單看到這張單（證明列表已載入），再切回評估中斷言空狀態並截圖
    await step('BC-03 保母評估中分頁：列表已載入，沒有這張單', page, async () => {
      await page.getByTestId('sitter-orders-tab-evaluating').click();
      await expect(page.getByTestId('sitter-orders-loading')).toHaveCount(0);
      await expect(page.getByTestId('sitter-orders-empty')).toBeVisible();
      await expect(page.getByTestId('sitter-orders-count')).toHaveText('共 0 筆');
      await expect(page.getByTestId('btn-go-order-eval')).toHaveCount(0);
      await expect(page.getByTestId(`sitter-order-total-${orderId}`)).toHaveCount(0);
    });

    await step('BC-04 保母評估頁：顯示「飼主已取消預約」，沒有接單 / 報價按鈕', page, async () => {
      await page.goto(`/sitter/eval/${orderId}`);
      await expect(page.getByTestId('sitter-order-eval-not-pending')).toHaveText(
        '飼主已取消預約，無法在此頁面操作。'
      );
      await expect(page.getByTestId('sitter-order-eval-btn-accept-original')).toHaveCount(0);
      await expect(page.getByTestId('sitter-order-eval-btn-confirm')).toHaveCount(0);
      // fix-batch12 U3：撤單後評估頁有返回列表的出口
      await expect(page.getByTestId('sitter-order-eval-back-to-list')).toHaveText('返回訂單列表');
    });

    await ownerContext.close();
  });

  /**
   * fix-batch12 J6：第 11 批把 POST /api/orders/{id}/reject 的保母身分改取自 token（BOLA），
   * 但真後端 journey 過去從未呼叫這支（CI run 40177738227：reject 0 次）。保母從評估頁 UI 婉拒，
   * 斷言實際發出的請求不帶 sitterId、飼主端看到已取消（非飼主撤單的文案）。
   */
  test('保母婉拒待評估訂單：飼主端顯示「此預約已取消」，請求不帶身分參數', async ({
    page,
    request,
    browser
  }) => {
    const { sitter, owner, orderId } = await apiStep(
      '前置：API 兜出 VERIFIED 保母 + 飼主 + PENDING 訂單（$600）',
      async () => {
        const ctx = await bootstrapPendingOrder(request, 'br', 'Journey婉拒方案');
        const order = await awaitOrderStatus(request, ctx.owner, ctx.orderId, ['PENDING']);
        return { ...ctx, status: order.status };
      }
    );

    await setBrowserAuth(page, sitter, 'sitter');
    const sitterDialogs = trackDialogs(page);
    const rejectCalls = trackOrderActionCalls(page, orderId, 'reject');
    const rejectReason = `Journey B 婉拒：當週檔期已滿-${Date.now()}`;

    await step('BR-01 保母從列表點入評估頁，展開婉拒並填寫原因', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('btn-go-order-eval').click();
      await expect(page).toHaveURL(new RegExp(`/sitter/eval/${orderId}$`));
      await page.getByTestId('sitter-order-eval-btn-reject').click();
      await page.getByTestId('sitter-order-eval-input-reject-reason').fill(rejectReason);
      await expect(page.getByTestId('sitter-order-eval-btn-submit-reject')).toBeEnabled();
    });

    await step('BR-02 確認婉拒：alert「已拒絕此訂單」，導回訂單列表', page, async () => {
      await page.getByTestId('sitter-order-eval-btn-submit-reject').click();
      await expectDialog(sitterDialogs, /確定要拒絕此訂單嗎/);
      await expectDialog(sitterDialogs, /已拒絕此訂單/);
      await expect(page).toHaveURL(/\/sitter\/orders$/, { timeout: 15000 });
      await awaitOrderStatus(request, sitter, orderId, ['CANCELLED']);
    });

    await apiStep(
      'BR-03 婉拒請求：POST /api/orders/{id}/reject 恰好一次、不帶 sitterId / ownerId；訂單 CANCELLED、非飼主撤單',
      async () => {
        expectSingleTokenOnlyCall(rejectCalls, 'reject');
        const detailRes = await request.get(`/api/orders/${orderId}`, {
          headers: apiAuthed(owner)
        });
        expect(detailRes.ok()).toBeTruthy();
        const detail = await detailRes.json();
        expect(detail.status).toBe('CANCELLED');
        expect(detail.cancelledBy ?? null).toBeNull();
        expect(detail.cancellableByOwner).toBe(false);
        return {
          rejectCalls,
          status: detail.status,
          cancelledBy: detail.cancelledBy ?? null,
          cancellableByOwner: detail.cancellableByOwner
        };
      }
    );

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    await setBrowserAuth(ownerPage, owner, 'client');

    await step(
      'BR-04 飼主訂單詳情：徽章「已取消」、橫幅「此預約已取消」、沒有取消預約鈕',
      ownerPage,
      async () => {
        await ownerPage.goto(`/owner/orders/${orderId}`);
        await expect(ownerPage.getByTestId('order-status-badge')).toHaveText('已取消');
        await expect(ownerPage.getByTestId('cancelled-status-banner')).toHaveText('此預約已取消');
        await expect(ownerPage.getByTestId('owner-cancel-booking-btn')).toHaveCount(0);
        await expect(ownerPage.getByTestId('btn-submit-payment-proof')).toHaveCount(0);
      }
    );

    await ownerContext.close();
  });
});
