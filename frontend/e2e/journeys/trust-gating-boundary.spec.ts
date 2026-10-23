import fs from 'fs';
import path from 'path';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { provisionAndLogin, switchRole, apiAuthed, loginAsSeedAdmin, bootstrapConfirmedOrder, awaitOrderStatus } from './helpers';
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

    const anonContext = await browser.newContext();
    const anonPage = await anonContext.newPage();
    await step('未登入訪客打開此保母的公開頁，應被 gate', anonPage, async () => {
      await anonPage.goto(`/sitter/${sitter.userId}/profile`);

      await expect(anonPage.getByTestId('public-profile-gated-banner')).toBeVisible({ timeout: 10000 });
      await expect(anonPage.getByTestId('public-profile-display-name')).not.toHaveText(/Journey保母E1/);
      await expect(anonPage.getByTestId('public-profile-plan-item')).not.toBeVisible();
      await expect(anonPage.getByTestId('public-profile-book-now-btn')).not.toBeVisible();
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

  test('保母停權後，公開頁被 gate，但停權前已存在的進行中訂單不受影響', async ({ request, browser }) => {
    const { sitter, owner, orderId } = await apiStep('前置：兜出一筆 CONFIRMED 訂單（此時保母仍為正常 VERIFIED 狀態）', async () => {
      return bootstrapConfirmedOrder(request, 'e2', 'Journey方案E2');
    });

    await apiStep('Admin 將此保母停權', async () => {
      const admin = await loginAsSeedAdmin(request);
      const suspendRes = await request.post(`/api/admin/sitters/${sitter.userId}/suspend`, {
        headers: { ...apiAuthed(admin), 'Idempotency-Key': randomUUID() },
        data: { reason: 'Journey E 邊界測試 - 停權' }
      });
      if (!suspendRes.ok()) {
        throw new Error(`suspend failed (${suspendRes.status()}): ${await suspendRes.text()}`);
      }
      return { status: suspendRes.status() };
    });

    const anonContext = await browser.newContext();
    const anonPage = await anonContext.newPage();
    await step('未登入訪客打開此保母的公開頁，應被 gate（停權跟未過審共用同一套 gating 邏輯）', anonPage, async () => {
      await anonPage.goto(`/sitter/${sitter.userId}/profile`);

      await expect(anonPage.getByTestId('public-profile-gated-banner')).toBeVisible({ timeout: 10000 });
    });
    await anonContext.close();

    await apiStep('停權前已存在的 CONFIRMED 訂單，Check-in 仍應正常成功（邊界：停權不影響進行中訂單）', async () => {
      const visitsRes = await request.get(`/api/orders/${orderId}/visits`, { headers: apiAuthed(sitter) });
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
    });
  });
});
