import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import {
  provisionAndLogin,
  switchRole,
  setBrowserAuth,
  loginAsSeedAdmin,
  apiAuthed,
  trackLoginRequests,
  expectStillAnonymous
} from './helpers';
import { step, apiStep, snap, trackDialogs } from '../support/evidence';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * TS-JOURNEY-01：保母上架全流程。
 * 打真後端 + 真資料庫（正式站），驗證「註冊 -> KYC -> 審核 -> 開單 -> 上架方案 -> 公開網址可見」
 * 這條跨模組串接是否真的打得通，不是各模組個別 mock 測試能驗到的。
 */
test.describe('Journey A: 保母上架全流程 (TS-JOURNEY-01)', () => {
  test.setTimeout(120000);

  test('保母從註冊到公開檔案上架，全流程可正常運作', async ({ page, request, browser }) => {
    // 1. API：建帳號（略過 OTP）+ 真實登入 + 切換為保母角色
    const sitter = await apiStep('建立保母帳號並切換角色', async () => {
      let account = await provisionAndLogin(request, 'a', 'SITTER', 'Journey保母A');
      account = await switchRole(request, account, 'SITTER');
      return account;
    });

    await setBrowserAuth(page, sitter, 'sitter');

    // 2. UI：提交 KYC
    await step('A-02 保母提交 KYC 證件與自拍照', page, async () => {
      // TS #2：switch-role 後 lazy 建立的 Profile 必須是 UNVERIFIED。徽章在 status API 回來前
      // 也會 fallback 成「未認證」（SitterKycSubmit.tsx kycStatus 預設值），所以先等真實回應再斷言徽章
      const initialStatus = page.waitForResponse(
        (r) => r.url().includes('/api/sitter/kyc/status') && r.request().method() === 'GET'
      );
      await page.goto('/sitter/kyc');
      const initialStatusRes = await initialStatus;
      expect(initialStatusRes.ok()).toBeTruthy();
      expect((await initialStatusRes.json()).data.kycStatus).toBe('UNVERIFIED');
      await expect(page.getByTestId('sitter-kyc-status-badge')).toHaveText('未認證');
      await page
        .getByTestId('sitter-kyc-id-front-input')
        .setInputFiles(path.join(__dirname, 'fixtures/kyc-id-front.jpg'));
      await page
        .getByTestId('sitter-kyc-selfie-input')
        .setInputFiles(path.join(__dirname, 'fixtures/kyc-selfie.jpg'));
      await snap(page, 'A-02 KYC 表單填寫完成');

      await page.getByTestId('sitter-kyc-submit-btn').click();
      await expect(page.getByTestId('sitter-kyc-status-badge')).toHaveText('審核中', {
        timeout: 15000
      });
      // U3：頂部橫幅要跟著變審核中，不能還掛著送出前的「尚未完成保母實名認證」
      await expect(page.getByTestId('sitter-kyc-banner')).toContainText('認證資料正在審核中');
      await expect(page.getByTestId('sitter-kyc-banner')).not.toContainText('尚未完成');
      await snap(page, 'A-02 KYC 送出後審核中');

      const statusRes = await request.get('/api/sitter/kyc/status', { headers: apiAuthed(sitter) });
      expect(statusRes.ok()).toBeTruthy();
      const statusBody = await statusRes.json();
      expect(statusBody.data.kycStatus).toBe('PENDING_REVIEW');
    });

    // 3. API：admin 撈待審清單找到這筆申請
    const admin = await loginAsSeedAdmin(request);
    const recordId: string = await apiStep('A-03 管理員查詢待審核清單撈到這筆申請', async () => {
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
    const adminPage = await browser.newPage();
    await setBrowserAuth(adminPage, admin, 'admin');
    trackDialogs(adminPage);
    await step('A-04 管理員於後台核准實名認證', adminPage, async () => {
      await adminPage.goto('/admin/kyc');

      const row = adminPage.getByTestId('admin-kyc-row').filter({ hasText: sitter.email });
      await expect(row).toBeVisible();
      await snap(adminPage, 'A-04 待審清單找到申請');
      await row.getByTestId('admin-kyc-review-btn').click();

      await expect(adminPage).toHaveURL(new RegExp(`/admin/kyc/${recordId}`));
      // 舊版 URL 一變就截圖，詳情 API 還沒回來，截到的仍是待審清單
      await expect(adminPage.getByTestId('admin-kyc-detail-approve-btn')).toBeVisible();
      // U1：證件照與自拍要真的看得到（過去兩欄 grid 溢出 390px AppShell，照片被裁掉）
      const main = await adminPage.locator('main').boundingBox();
      for (const testId of ['admin-kyc-detail-id-front-img', 'admin-kyc-detail-selfie-img']) {
        const img = adminPage.getByTestId(testId);
        await expect(img).toBeVisible();
        await expect
          .poll(() => img.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0))
          .toBe(true);
        const box = await img.boundingBox();
        expect(box!.width, `${testId} 寬度`).toBeGreaterThan(250);
        expect(box!.x + box!.width, `${testId} 右緣`).toBeLessThanOrEqual(main!.x + main!.width);
      }
      await snap(adminPage, 'A-04 審核詳情頁');
      await adminPage.getByTestId('admin-kyc-detail-approve-btn').click();
      // TS-JOURNEY-01 #5：頁面要顯示審核通過訊息（AdminKycDetail 顯示 1.5 秒後才跳回清單）
      await expect(adminPage.getByText('實名認證審核已通過！')).toBeVisible();
      // U2：導回清單前徽章就要反映結果（過去 1.5 秒內仍顯示 PENDING）
      await expect(adminPage.getByTestId('admin-kyc-detail-status-badge')).toHaveText(/^已通過/);
      await snap(adminPage, 'A-04 核准成功訊息');
      await expect(adminPage).toHaveURL(/\/admin\/kyc$/, { timeout: 10000 });
      // 核准後回到待審清單，這筆申請應該消失（舊版沒有這張圖，看不出核准有沒有生效）
      await expect(
        adminPage.getByTestId('admin-kyc-row').filter({ hasText: sitter.email })
      ).toHaveCount(0, {
        timeout: 10000
      });
    });
    await adminPage.close();

    // 5. 斷言 Profile.kycStatus = VERIFIED
    await apiStep('A-05 確認保母 KYC 狀態已通過', async () => {
      const statusRes = await request.get('/api/sitter/kyc/status', { headers: apiAuthed(sitter) });
      const statusBody = await statusRes.json();
      expect(statusBody.data.kycStatus).toBe('VERIFIED');
      return statusBody.data;
    });

    // 6. API：開啟接單狀態
    await apiStep('A-06 保母開啟接單狀態', async () => {
      const openRes = await request.put('/api/sitter/kyc/open', {
        headers: apiAuthed(sitter),
        data: { isOpen: true }
      });
      expect(openRes.ok()).toBeTruthy();
      return openRes.json();
    });

    // 6.5. API：設定公開檔案顯示名稱（Profile.displayName 預設是空的，SD-018 是獨立的資料欄位，
    // 不會沿用 User.fullName，未設定的話公開頁的 <h1> 會是空字串）
    const displayName = `Journey保母A公開顯示名稱-${Date.now()}`;
    await apiStep('A-06.5 保母設定公開檔案顯示名稱', async () => {
      const selfRes = await request.get(`/api/sitter/profile/${sitter.userId}`, {
        headers: apiAuthed(sitter)
      });
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
      return updateRes.json();
    });

    // 7. UI：建立多個服務方案（TS #7：不同 applicablePetTypes/價格，兩筆都 CAT/600 的話測不出欄位串錯）
    const plans = [
      {
        name: `Journey方案A-常態照護-${Date.now()}`,
        price: 600,
        petType: 'CAT',
        petLabel: '🐱 貓咪'
      },
      {
        name: `Journey方案A-陪伴散步-${Date.now()}`,
        price: 900,
        petType: 'DOG',
        petLabel: '🐶 狗狗'
      }
    ];
    await step('A-07 保母上架多個服務方案', page, async () => {
      await page.goto('/sitter/plans');
      for (const plan of plans) {
        await page.getByTestId('sitter-plan-btn-add').click();
        await page.getByTestId('sitter-plan-input-name').fill(plan.name);
        await page.getByTestId('sitter-plan-input-price').fill(String(plan.price));
        await page.getByTestId('sitter-plan-input-capacity').fill('2');
        await page.getByTestId('sitter-plan-input-duration').fill('60');
        // openModal(null) 新增方案時預設已勾選 CAT（見 SitterPlans.tsx openModal）：
        // CAT 方案不用點（點了反而取消勾選）；非 CAT 方案先點 CAT 取消、再點目標類型
        if (plan.petType !== 'CAT') {
          await page.getByTestId('sitter-plan-checkbox-pet-CAT').click();
          await page.getByTestId(`sitter-plan-checkbox-pet-${plan.petType}`).click();
        }
        await snap(page, `A-07 方案表單：${plan.name}`);
        await page.getByTestId('sitter-plan-btn-save').click();
        const card = page
          .locator('[data-testid^="sitter-plan-card-"]')
          .filter({ hasText: plan.name });
        await expect(card).toBeVisible({ timeout: 10000 });
        await expect(card).toContainText(plan.petLabel);
        // U7（驗收 N2）：價格區塊不得溢出卡片右邊界（名稱含時間戳這種長英數字串時曾被擠出去）
        const cardBox = (await card.boundingBox())!;
        const priceBox = (await card.locator('[data-testid^="sitter-plan-price-"]').boundingBox())!;
        expect(priceBox.x + priceBox.width).toBeLessThanOrEqual(cardBox.x + cardBox.width);
        for (const other of plans.filter((o) => o.petType !== plan.petType)) {
          await expect(card).not.toContainText(other.petLabel);
        }
      }
    });

    // 8. UI：未登入瀏覽器打開公開網址，驗證真正的公開頁面可用
    const anonContext = await browser.newContext();
    const anonPage = await anonContext.newPage();
    const anonLogins = trackLoginRequests(anonPage);
    await step('A-08 未登入訪客打開保母公開網址', anonPage, async () => {
      await anonPage.goto(`/sitter/${sitter.userId}/profile`);

      await expect(anonPage.getByTestId('public-profile-display-name')).toHaveText(displayName);
      await expect(anonPage.getByTestId('public-profile-gated-banner')).not.toBeVisible();
      for (const plan of plans) {
        const item = anonPage
          .getByTestId('public-profile-plan-item')
          .filter({ hasText: plan.name });
        await expect(item).toBeVisible();
        await expect(item).toContainText(`NT$ ${plan.price}`);
      }
      // C1 迴歸 (X2)：匿名公開頁不得在背景以種子帳號登入
      await expectStillAnonymous(anonPage, anonLogins);
    });

    // 8. API：匿名查詢公開檔案與方案清單（TS #8 技術校驗，UI 之外再釘一次資料層）
    await apiStep('A-08 匿名查詢公開檔案與方案 API', async () => {
      const profileRes = await anonContext.request.get(`/api/sitter/profile/${sitter.userId}`);
      expect(profileRes.ok()).toBeTruthy();
      const profile = await profileRes.json();
      expect(profile.gated).toBe(false);
      expect(profile.displayName).toBe(displayName);

      const plansRes = await anonContext.request.get(`/api/sitters/${sitter.userId}/plans`);
      expect(plansRes.ok()).toBeTruthy();
      const published: any[] = (await plansRes.json()).data;
      for (const plan of plans) {
        const found = published.find((p) => p.name === plan.name);
        expect(found, `公開方案清單應包含 ${plan.name}`).toBeTruthy();
        expect(found.isActive).toBe(true);
        expect(Number(found.price)).toBe(plan.price);
        expect(found.applicablePetTypes).toEqual([plan.petType]);
        // U6（驗收 N1）：預設 SOP 依寵物類型給，狗狗方案不得帶「清理砂盆」
        if (plan.petType === 'DOG') {
          expect(found.defaultTasks).toContain('散步遛狗');
          expect(found.defaultTasks).not.toContain('清理砂盆');
        } else {
          expect(found.defaultTasks).toContain('清理砂盆');
        }
      }
      return { gated: profile.gated, displayName: profile.displayName, plans: published };
    });
    await anonContext.close();
  });
});
