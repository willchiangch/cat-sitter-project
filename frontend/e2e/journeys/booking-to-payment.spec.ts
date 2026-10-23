import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect, type Page } from '@playwright/test';
import {
  provisionAndLogin,
  setBrowserAuth,
  bootstrapVerifiedSitter,
  awaitOrderStatus,
  pollUntil,
  apiAuthed,
  expectSignedPaymentProof
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

    await step('B-01 未登入訪客在公開頁點立即預約，導向註冊頁並帶 redirect', ownerPage, async () => {
      await ownerPage.goto(`/sitter/${sitter.userId}/profile`);
      await expect(ownerPage.getByTestId('public-profile-book-now-btn')).toBeVisible();
      await snap(ownerPage, 'B-01 保母公開頁');
      await ownerPage.getByTestId('public-profile-book-now-btn').click();
      await expect(ownerPage).toHaveURL(
        new RegExp(`/register\\?redirect=${encodeURIComponent(bookingPath).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)
      );
    });

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
      await expect(ownerPage).toHaveURL(new RegExp(bookingPath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), { timeout: 15000 });
      await expect(ownerPage.getByRole('heading', { name: '選擇服務方案' })).toBeVisible();
    });

    await step('B-03 飼主建立寵物資料並上傳頭像', ownerPage, async () => {
      await ownerPage.goto('/pets');
      await ownerPage.getByTestId('btn-add-pet').click();
      await ownerPage.getByTestId('input-pet-name').fill('Journey測試貓B');
      await ownerPage.getByTestId('avatar-file-input').setInputFiles(path.join(__dirname, 'fixtures/pet-avatar.jpg'));
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
      await ownerPage.getByTestId('input-payment-file').setInputFiles(path.join(__dirname, 'fixtures/kyc-selfie.jpg'));
      await ownerPage.getByTestId('checkbox-disclaimer-agreed').check();
      await snap(ownerPage, 'B-06 付款表單填寫完成');
      await ownerPage.getByTestId('btn-submit-payment-proof').click();
      await expectDialog(ownerDialogs, /付款憑證提交成功/);
      await awaitOrderStatus(request, owner, orderId, ['PAID']);
      // 舊版 API poll 到 PAID 就直接截圖，畫面可能還停在付款表單；reload 後斷言 PAID 畫面再截
      await ownerPage.reload();
      await expect(ownerPage.getByTestId('btn-submit-payment-proof')).toHaveCount(0, { timeout: 10000 });
      // B1 迴歸：飼主看自己的憑證也要走簽名網址
      await expectSignedPaymentProof(ownerPage);
    });

    await step('B-07 保母核對入帳', page, async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('btn-verify-payment')).toBeVisible({ timeout: 10000 });
      // B1 迴歸：保母核對面板的憑證預覽必須顯示，且是簽名網址而非公開路徑
      await expect(page.getByTestId('verify-last-five')).toHaveText('12345');
      await expectSignedPaymentProof(page.getByTestId('payment-verification-panel'));
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
          const res = await request.get(`/api/care-notes/${sitter.userId}/${owner.userId}`, { headers: apiAuthed(owner) });
          return res.ok() ? res.json() : null;
        },
        (body) => JSON.stringify(body).includes(careNoteContent)
      );

      await ownerPage.goto(`/care-notes/view/${sitter.userId}/${owner.userId}`);
      await expect(ownerPage.getByText(careNoteContent)).toBeVisible({ timeout: 10000 });
    });

    await ownerContext.close();
  });
});
