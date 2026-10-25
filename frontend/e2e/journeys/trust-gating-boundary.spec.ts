import fs from 'fs';
import path from 'path';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import {
  provisionAndLogin,
  switchRole,
  apiAuthed,
  loginAsSeedAdmin,
  bootstrapConfirmedOrder,
  awaitOrderStatus,
  trackLoginRequests,
  expectStillAnonymous
} from './helpers';
import { step, apiStep } from '../support/evidence';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * TS-JOURNEY-05：信任/門禁邊界，負向測試。
 * 打真後端 + 真資料庫（正式站），驗證 PRD-017 稽核修復過的兩個邊界：
 * 1. 未通過 KYC / 已停權的保母，匿名公開頁必須被模糊化 (gated)
 * 2. 停權只擋新預約與公開頁，不影響停權前就已存在的進行中訂單
 */
test.describe('Journey E: 信任/門禁邊界 (TS-JOURNEY-05)', () => {
  test.setTimeout(120000);

  test('未通過 KYC 的保母，匿名公開頁應被模糊化', async ({ request, browser }) => {
    const sitter = await apiStep('前置：建立保母帳號並送出 KYC，但不經過 admin 審核', async () => {
      let account = await provisionAndLogin(request, 'e1', 'SITTER', 'Journey保母E1未過審');
      account = await switchRole(request, account, 'SITTER');

      const kycRes = await request.post('/api/sitter/kyc', {
        headers: { ...apiAuthed(account), 'Idempotency-Key': randomUUID() },
        multipart: {
          idCardFront: {
            name: 'front.jpg',
            mimeType: 'image/jpeg',
            buffer: fs.readFileSync(path.join(__dirname, 'fixtures/kyc-id-front.jpg'))
          },
          selfie: {
            name: 'selfie.jpg',
            mimeType: 'image/jpeg',
            buffer: fs.readFileSync(path.join(__dirname, 'fixtures/kyc-selfie.jpg'))
          }
        }
      });
      if (!kycRes.ok()) {
        throw new Error(`kyc submit failed (${kycRes.status()}): ${await kycRes.text()}`);
      }
      return account;
    });

    // TS #1：「未過審」這個前提要釘住，否則 Profile 實際停在 UNVERIFIED 時 step 2 照樣 gated、照樣綠燈
    await apiStep('前置：確認保母 Profile 停在 PENDING_REVIEW（未經審核）', async () => {
      const statusRes = await request.get('/api/sitter/kyc/status', { headers: apiAuthed(sitter) });
      expect(statusRes.ok()).toBeTruthy();
      const statusBody = await statusRes.json();
      expect(statusBody.data.kycStatus).toBe('PENDING_REVIEW');
      return statusBody.data;
    });

    // fix-batch12 J4（驗收 A7）：開發用自助升級端點只在 local profile 註冊（@Profile("local")），
    // 正式站必須不存在；過去只有 JUnit（GatekeeperSubscriptionProdProfileTest）證明，正式站沒有證據
    await apiStep(
      '保母打開發用自助升級端點 /subscription/mock，正式站應回 404 且訂閱仍為 FREE',
      async () => {
        const mockRes = await request.post('/api/sitter/gatekeeper/subscription/mock', {
          headers: apiAuthed(sitter),
          data: { planTier: 'ULTIMATE' }
        });
        expect(mockRes.status()).toBe(404);
        const subRes = await request.get('/api/sitter/gatekeeper/subscription', {
          headers: apiAuthed(sitter)
        });
        expect(subRes.ok()).toBeTruthy();
        const subscription = await subRes.json();
        expect(subscription.planTier).toBe('FREE');
        return { mockStatus: mockRes.status(), subscription };
      }
    );

    const anonContext = await browser.newContext();
    const anonPage = await anonContext.newPage();
    const anonLogins = trackLoginRequests(anonPage);
    await step('未登入訪客打開此保母的公開頁，應被 gate', anonPage, async () => {
      await anonPage.goto(`/sitter/${sitter.userId}/profile`);

      await expect(anonPage.getByTestId('public-profile-gated-banner')).toBeVisible({
        timeout: 10000
      });
      await expect(anonPage.getByTestId('public-profile-display-name')).not.toHaveText(
        /Journey保母E1/
      );
      await expect(anonPage.getByTestId('public-profile-plan-item')).not.toBeVisible();
      await expect(anonPage.getByTestId('public-profile-book-now-btn')).not.toBeVisible();
      // C1 迴歸 (X2)：匿名公開頁不得在背景以種子帳號登入
      await expectStillAnonymous(anonPage, anonLogins);
    });

    // TS #2 技術校驗：匿名（不帶 token）查公開檔案 API，資料層也必須是 gated
    await apiStep('匿名查詢此保母公開檔案 API，應回 gated=true', async () => {
      const profileRes = await anonContext.request.get(`/api/sitter/profile/${sitter.userId}`);
      expect(profileRes.ok()).toBeTruthy();
      const profile = await profileRes.json();
      expect(profile.gated).toBe(true);
      expect(profile.displayName).toBe('保母休息中');
      return profile;
    });
    await anonContext.close();
  });

  test('保母停權後，公開頁被 gate，但停權前已存在的進行中訂單不受影響', async ({
    request,
    browser
  }) => {
    const { sitter, owner, orderId } = await apiStep(
      '前置：兜出一筆 CONFIRMED 訂單（此時保母仍為正常 VERIFIED 狀態）',
      async () => {
        return bootstrapConfirmedOrder(request, 'e2', 'Journey方案E2');
      }
    );

    await apiStep('Admin 將此保母停權', async () => {
      // 停權前先確認 isOpen=true，下面的 isOpen=false 才證明得了是停權造成的轉移
      const openBeforeRes = await request.get('/api/sitter/kyc/open', {
        headers: apiAuthed(sitter)
      });
      expect(openBeforeRes.ok()).toBeTruthy();
      const isOpenBefore = (await openBeforeRes.json()).data.isOpen;
      expect(isOpenBefore).toBe(true);

      const admin = await loginAsSeedAdmin(request);
      const suspendRes = await request.post(`/api/admin/sitters/${sitter.userId}/suspend`, {
        headers: { ...apiAuthed(admin), 'Idempotency-Key': randomUUID() },
        data: { reason: 'Journey E 邊界測試 - 停權' }
      });
      if (!suspendRes.ok()) {
        throw new Error(`suspend failed (${suspendRes.status()}): ${await suspendRes.text()}`);
      }

      // TS 測試 2 #2：kycStatus → SUSPENDED、isOpen → false（KycServiceImpl.suspendSitter）
      const statusRes = await request.get('/api/sitter/kyc/status', { headers: apiAuthed(sitter) });
      expect(statusRes.ok()).toBeTruthy();
      const kycStatus = (await statusRes.json()).data.kycStatus;
      expect(kycStatus).toBe('SUSPENDED');
      const openRes = await request.get('/api/sitter/kyc/open', { headers: apiAuthed(sitter) });
      expect(openRes.ok()).toBeTruthy();
      const isOpen = (await openRes.json()).data.isOpen;
      expect(isOpen).toBe(false);
      // S1：停權前的 isOpen 也寫進證據 JSON，TS 測試 2 #2「三個值都寫進證據」才完整
      return { isOpenBefore, status: suspendRes.status(), kycStatus, isOpen };
    });

    const anonContext = await browser.newContext();
    const anonPage = await anonContext.newPage();
    const anonLogins = trackLoginRequests(anonPage);
    await step(
      '未登入訪客打開此保母的公開頁，應被 gate（停權跟未過審共用同一套 gating 邏輯）',
      anonPage,
      async () => {
        await anonPage.goto(`/sitter/${sitter.userId}/profile`);

        await expect(anonPage.getByTestId('public-profile-gated-banner')).toBeVisible({
          timeout: 10000
        });
        await expect(anonPage.getByTestId('public-profile-display-name')).not.toHaveText(
          /Journey保母e2/
        );
        await expect(anonPage.getByTestId('public-profile-plan-item')).not.toBeVisible();
        await expect(anonPage.getByTestId('public-profile-book-now-btn')).not.toBeVisible();
        // C1 迴歸 (X2)：匿名公開頁不得在背景以種子帳號登入
        await expectStillAnonymous(anonPage, anonLogins);
      }
    );

    await apiStep('匿名查詢停權保母公開檔案 API，應回 gated=true', async () => {
      const profileRes = await anonContext.request.get(`/api/sitter/profile/${sitter.userId}`);
      expect(profileRes.ok()).toBeTruthy();
      const profile = await profileRes.json();
      expect(profile.gated).toBe(true);
      expect(profile.displayName).toBe('保母休息中');
      return profile;
    });
    await anonContext.close();

    await apiStep(
      '停權前已存在的 CONFIRMED 訂單，Check-in 仍應正常成功（邊界：停權不影響進行中訂單）',
      async () => {
        const visitsRes = await request.get(`/api/orders/${orderId}/visits`, {
          headers: apiAuthed(sitter)
        });
        if (!visitsRes.ok()) {
          throw new Error(`get visits failed (${visitsRes.status()}): ${await visitsRes.text()}`);
        }
        const visits = await visitsRes.json();
        const visitId = visits[0].id;

        const startRes = await request.post(`/api/visits/${visitId}/start`, {
          headers: { ...apiAuthed(sitter), 'Idempotency-Key': randomUUID() }
        });
        // 這裡刻意用「明確斷言 200」把這個目前程式碼的真實行為釘進測試——
        // 如果之後有人不小心把停權邏輯擴大成連 Check-in 都擋，這支測試會直接紅燈提醒
        expect(startRes.ok()).toBeTruthy();

        const order = await awaitOrderStatus(request, owner, orderId, ['IN_PROGRESS']);
        return { checkIn: await startRes.json(), order };
      }
    );
  });
});
