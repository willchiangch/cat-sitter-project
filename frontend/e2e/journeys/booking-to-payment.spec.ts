import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect, type Page } from '@playwright/test';
import {
  provisionAndLogin,
  setBrowserAuth,
  bootstrapVerifiedSitter,
  awaitOrderStatus,
  pollUntil,
  apiAuthed
} from './helpers';

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

  test('飼主從公開頁下單到保母確認入帳，全流程可正常運作', async ({ page, request, browser }, testInfo) => {
    const sitter = await test.step('前置：兜出一個 VERIFIED+isOpen+有方案的保母', async () => {
      return bootstrapVerifiedSitter(request, 'b', 'Journey方案B');
    });

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    const bookingPath = `/booking/${sitter.userId}`;

    await test.step('未登入訪客在公開頁點立即預約，導向註冊頁並帶 redirect', async () => {
      await ownerPage.goto(`/sitter/${sitter.userId}/profile`);
      await ownerPage.getByTestId('public-profile-book-now-btn').click();
      await expect(ownerPage).toHaveURL(
        new RegExp(`/register\\?redirect=${encodeURIComponent(bookingPath).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)
      );
      await testInfo.attach('B-01-導向註冊頁帶redirect', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    const owner = await provisionAndLogin(request, 'b', 'OWNER', 'Journey飼主B');

    await test.step('改走登入並驗證 redirect 導回下單頁', async () => {
      await ownerPage.getByRole('link', { name: '已經有帳號了？返回登入' }).click();
      await expect(ownerPage).toHaveURL(new RegExp(`/login\\?redirect=`));
      await ownerPage.getByTestId('login-email-input').fill(owner.email);
      await ownerPage.getByTestId('login-password-input').fill(owner.password);
      await ownerPage.getByTestId('login-submit-btn').click();
      await expect(ownerPage).toHaveURL(new RegExp(bookingPath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), { timeout: 15000 });
      await testInfo.attach('B-02-redirect生效回到下單頁', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    await test.step('飼主建立寵物資料並上傳頭像', async () => {
      await ownerPage.goto('/pets');
      await ownerPage.getByTestId('btn-add-pet').click();
      await ownerPage.getByTestId('input-pet-name').fill('Journey測試貓B');
      await ownerPage.getByTestId('avatar-file-input').setInputFiles(path.join(__dirname, 'fixtures/pet-avatar.jpg'));
      await ownerPage.getByTestId('btn-save-pet').click();
      // 存檔後 PetManager 會自動切到該寵物的編輯面板，畫面上同時有「寵物卡片」與
      // 「編輯 XXX 的基本資料」兩處都含寵物名稱文字，用寬鬆的 getByText 會撞 strict mode
      await expect(
        ownerPage.getByTestId('pet-card').getByRole('heading', { name: 'Journey測試貓B' })
      ).toBeVisible({ timeout: 10000 });
      await testInfo.attach('B-03-寵物建立完成', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    await test.step('飼主完成預約送單', async () => {
      await ownerPage.goto(bookingPath);
      await expect(ownerPage.getByRole('heading', { name: '選擇服務方案' })).toBeVisible();
      await ownerPage.click('[data-testid="client-booking-plan-card-0"]');
      await expect(ownerPage.getByRole('heading', { name: '排程配置' })).toBeVisible();
      await selectFirstAvailableDate(ownerPage, 0, 0);
      await testInfo.attach('B-04-選擇日期完成', { body: await ownerPage.screenshot(), contentType: 'image/png' });
      await ownerPage.click('[data-testid="client-booking-btn-step1-next"]');
      await expect(ownerPage.getByRole('heading', { name: '預約摘要' })).toBeVisible();
      await testInfo.attach('B-04-預約摘要', { body: await ownerPage.screenshot(), contentType: 'image/png' });
      await ownerPage.click('[data-testid="client-booking-btn-submit"]');
      // 送出成功後 PublicBookingPage 會 navigate 到 /owner/orders/{orderId}，用這個導航
      // 當作「真的送出成功」的訊號，不能只 await click() 就當作完成——click() 只保證事件
      // 有派發，不保證背後的非同步送單/導航已經跑完
      await expect(ownerPage).toHaveURL(/\/owner\/orders\/[0-9a-f-]+/, { timeout: 15000 });
      await testInfo.attach('B-04-送出預約', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    const orderId = ownerPage.url().match(/\/owner\/orders\/([0-9a-f-]+)/)?.[1];
    if (!orderId) throw new Error(`無法從網址解析 orderId: ${ownerPage.url()}`);

    // 實測發現：正式站極短時間內（約 1 秒內）用不同身份查詢剛寫入的訂單有機率撲空
    // （見 awaitOrderStatus 註解），下單後不能立刻切換身份查列表頁，要先 poll 確認資料已可讀
    await test.step('確認訂單已可被查詢到', async () => {
      await awaitOrderStatus(request, owner, orderId, ['PENDING']);
    });

    await setBrowserAuth(page, sitter, 'sitter');
    page.on('dialog', (dialog) => dialog.accept());

    await test.step('保母原價直接接受訂單', async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('btn-go-order-eval').click();
      await expect(page.getByTestId('sitter-order-eval-btn-accept-original')).toBeVisible();
      await page.getByTestId('sitter-order-eval-btn-accept-original').click();
      await expect(page).toHaveURL(/\/sitter\/orders/, { timeout: 15000 });
      await awaitOrderStatus(request, sitter, orderId, ['PENDING_PAYMENT']);
      await testInfo.attach('B-05-保母接單完成', { body: await page.screenshot(), contentType: 'image/png' });
    });

    await test.step('飼主上傳線下付款憑證', async () => {
      await ownerPage.goto(`/owner/orders/${orderId}`);
      await ownerPage.getByTestId('input-payment-last-five').fill('12345');
      await ownerPage.getByTestId('input-payment-file').setInputFiles(path.join(__dirname, 'fixtures/kyc-selfie.jpg'));
      await ownerPage.getByTestId('checkbox-disclaimer-agreed').check();
      await testInfo.attach('B-06-付款表單填寫完成', { body: await ownerPage.screenshot(), contentType: 'image/png' });
      await ownerPage.getByTestId('btn-submit-payment-proof').click();
      await awaitOrderStatus(request, owner, orderId, ['PAID']);
      await testInfo.attach('B-06-付款憑證送出', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    await test.step('保母核對入帳', async () => {
      await page.goto('/sitter/orders');
      await page.getByTestId('sitter-orders-tab-ongoing').click();
      await expect(page.getByTestId('btn-verify-payment')).toBeVisible({ timeout: 10000 });
      await page.getByTestId('btn-verify-payment').click();
      await awaitOrderStatus(request, sitter, orderId, ['CONFIRMED']);
      await testInfo.attach('B-07-保母核對入帳完成', { body: await page.screenshot(), contentType: 'image/png' });
    });

    await test.step('雙方在照護記事本互動', async () => {
      const careNoteContent = `Journey B 記事本測試內容-${Date.now()}`;
      await page.goto(`/care-notes/manage/${sitter.userId}/${owner.userId}`);
      await page.getByTestId('sitter-carenote-add-item-OTHER').click();
      await page.getByTestId('sitter-carenote-item-title').fill('注意事項');
      await page.getByTestId('sitter-carenote-item-content').fill(careNoteContent);
      await page.getByTestId('sitter-carenote-save-btn').click();
      await testInfo.attach('B-08-保母寫入記事本', { body: await page.screenshot(), contentType: 'image/png' });

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
      await testInfo.attach('B-08-飼主看到記事本內容', { body: await ownerPage.screenshot(), contentType: 'image/png' });
    });

    await ownerContext.close();
  });
});
