import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import {
  provisionAndLogin,
  switchRole,
  setBrowserAuth,
  loginAsSeedAdmin,
  apiAuthed
} from './helpers';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * TS-JOURNEY-01：保母上架全流程。
 * 打真後端 + 真資料庫（正式站），驗證「註冊 -> KYC -> 審核 -> 開單 -> 上架方案 -> 公開網址可見」
 * 這條跨模組串接是否真的打得通，不是各模組個別 mock 測試能驗到的。
 */
test.describe('Journey A: 保母上架全流程 (TS-JOURNEY-01)', () => {
  test.setTimeout(120000);

  test('保母從註冊到公開檔案上架，全流程可正常運作', async ({ page, request, browser }, testInfo) => {
    // 1. API：建帳號（略過 OTP）+ 真實登入 + 切換為保母角色
    let sitter = await test.step('建立保母帳號並切換角色', async () => {
      let account = await provisionAndLogin(request, 'a', 'SITTER', 'Journey保母A');
      account = await switchRole(request, account, 'SITTER');
      return account;
    });

    await setBrowserAuth(page, sitter, 'sitter');

    // 2. UI：提交 KYC
    await test.step('保母提交 KYC 證件與自拍照', async () => {
      await page.goto('/sitter/kyc');
      await page.getByTestId('sitter-kyc-id-front-input').setInputFiles(
        path.join(__dirname, 'fixtures/kyc-id-front.jpg')
      );
      await page.getByTestId('sitter-kyc-selfie-input').setInputFiles(
        path.join(__dirname, 'fixtures/kyc-selfie.jpg')
      );
      await testInfo.attach('A-02-KYC表單填寫完成', { body: await page.screenshot(), contentType: 'image/png' });

      await page.getByTestId('sitter-kyc-submit-btn').click();
      await expect(page.getByTestId('sitter-kyc-status-badge')).toHaveText('審核中', { timeout: 15000 });
      await testInfo.attach('A-02-KYC送出後審核中', { body: await page.screenshot(), contentType: 'image/png' });

      const statusRes = await request.get('/api/sitter/kyc/status', { headers: apiAuthed(sitter) });
      expect(statusRes.ok()).toBeTruthy();
      const statusBody = await statusRes.json();
      expect(statusBody.data.kycStatus).toBe('PENDING_REVIEW');
    });

    // 3. API：admin 撈待審清單找到這筆申請
    const admin = await loginAsSeedAdmin(request);
    const recordId: string = await test.step('管理員查詢待審核清單撈到這筆申請', async () => {
      const pendingRes = await request.get('/api/admin/kyc/pending?page=0&size=50', {
        headers: apiAuthed(admin)
      });
      expect(pendingRes.ok()).toBeTruthy();
      const pendingBody = await pendingRes.json();
      const record = pendingBody.data.content.find((r: any) => r.email === sitter.email);
      expect(record, `pending KYC list 應包含 ${sitter.email}`).toBeTruthy();
      return record.recordId;
    });

    // 4. UI：admin 於後台核准
    await test.step('管理員於後台核准實名認證', async () => {
      const adminPage = await browser.newPage();
      await setBrowserAuth(adminPage, admin, 'admin');
      await adminPage.goto('/admin/kyc');

      const row = adminPage.getByTestId('admin-kyc-row').filter({ hasText: sitter.email });
      await expect(row).toBeVisible();
      await testInfo.attach('A-04-待審清單找到申請', { body: await adminPage.screenshot(), contentType: 'image/png' });
      await row.getByTestId('admin-kyc-review-btn').click();

      await expect(adminPage).toHaveURL(new RegExp(`/admin/kyc/${recordId}`));
      await testInfo.attach('A-04-審核詳情頁', { body: await adminPage.screenshot(), contentType: 'image/png' });
      await adminPage.getByTestId('admin-kyc-detail-approve-btn').click();
      await expect(adminPage).toHaveURL(/\/admin\/kyc$/, { timeout: 10000 });
      await adminPage.close();
    });

    // 5. 斷言 Profile.kycStatus = VERIFIED
    await test.step('確認保母 KYC 狀態已通過', async () => {
      const statusRes = await request.get('/api/sitter/kyc/status', { headers: apiAuthed(sitter) });
      const statusBody = await statusRes.json();
      expect(statusBody.data.kycStatus).toBe('VERIFIED');
    });

    // 6. API：開啟接單狀態
    await test.step('保母開啟接單狀態', async () => {
      const openRes = await request.put('/api/sitter/kyc/open', {
        headers: apiAuthed(sitter),
        data: { isOpen: true }
      });
      expect(openRes.ok()).toBeTruthy();
    });

    // 6.5. API：設定公開檔案顯示名稱（Profile.displayName 預設是空的，SD-018 是獨立的資料欄位，
    // 不會沿用 User.fullName，未設定的話公開頁的 <h1> 會是空字串）
    const displayName = `Journey保母A公開顯示名稱-${Date.now()}`;
    await test.step('保母設定公開檔案顯示名稱', async () => {
      const selfRes = await request.get(`/api/sitter/profile/${sitter.userId}`, { headers: apiAuthed(sitter) });
      expect(selfRes.ok()).toBeTruthy();
      const selfProfile = await selfRes.json();
      const updateRes = await request.put('/api/sitter/profile', {
        headers: apiAuthed(sitter),
        data: {
          displayName,
          bio: 'Journey A 自動化測試用保母自介',
          isVisible: true,
          tags: ['細心'],
          serviceAreas: [],
          version: selfProfile.version
        }
      });
      expect(updateRes.ok()).toBeTruthy();
    });

    // 7. UI：建立多個服務方案
    const planNames = [`Journey方案A-常態照護-${Date.now()}`, `Journey方案A-陪伴散步-${Date.now()}`];
    await test.step('保母上架多個服務方案', async () => {
      await page.goto('/sitter/plans');
      for (const planName of planNames) {
        await page.getByTestId('sitter-plan-btn-add').click();
        await page.getByTestId('sitter-plan-input-name').fill(planName);
        await page.getByTestId('sitter-plan-input-price').fill('600');
        await page.getByTestId('sitter-plan-input-capacity').fill('2');
        await page.getByTestId('sitter-plan-input-duration').fill('60');
        // 注意：openModal(null) 新增方案時預設就已勾選「CAT」（見 SitterPlans.tsx openModal），
        // 這裡不用再點一次——點下去反而會把預設值切掉，導致「請至少選擇一種適用寵物類型」擋在儲存
        await page.getByTestId('sitter-plan-btn-save').click();
        await expect(page.getByText(planName)).toBeVisible({ timeout: 10000 });
      }
      await testInfo.attach('A-07-方案清單', { body: await page.screenshot(), contentType: 'image/png' });
    });

    // 8. UI：未登入瀏覽器打開公開網址，驗證真正的公開頁面可用
    await test.step('未登入訪客打開保母公開網址', async () => {
      const anonContext = await browser.newContext();
      const anonPage = await anonContext.newPage();
      await anonPage.goto(`/sitter/${sitter.userId}/profile`);

      await expect(anonPage.getByTestId('public-profile-display-name')).toHaveText(displayName);
      await expect(anonPage.getByTestId('public-profile-gated-banner')).not.toBeVisible();
      for (const planName of planNames) {
        await expect(anonPage.getByText(planName)).toBeVisible();
      }
      await testInfo.attach('A-08-未登入公開頁', { body: await anonPage.screenshot(), contentType: 'image/png' });
      await anonContext.close();
    });
  });
});
