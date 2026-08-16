import fs from 'fs';
import path from 'path';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { provisionAndLogin, switchRole, apiAuthed, loginAsSeedAdmin, bootstrapConfirmedOrder, awaitOrderStatus } from './helpers';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * TS-JOURNEY-05：信任/門禁邊界，負向測試。
 * 打真後端 + 真資料庫（正式站），驗證 PRD-017 稽核修復過的兩個邊界：
 * 1. 未通過 KYC / 已停權的保母，匿名公開頁必須被模糊化 (gated)
 * 2. 停權只擋新預約與公開頁，不影響停權前就已存在的進行中訂單
 */
test.describe('Journey E: 信任/門禁邊界 (TS-JOURNEY-05)', () => {
  test.setTimeout(120000);

  test('未通過 KYC 的保母，匿名公開頁應被模糊化', async ({ request, browser }, testInfo) => {
    const sitter = await test.step('前置：建立保母帳號並送出 KYC，但不經過 admin 審核', async () => {
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

    await test.step('未登入訪客打開此保母的公開頁，應被 gate', async () => {
      const anonContext = await browser.newContext();
      const anonPage = await anonContext.newPage();
      await anonPage.goto(`/sitter/${sitter.userId}/profile`);

      await expect(anonPage.getByTestId('public-profile-gated-banner')).toBeVisible({ timeout: 10000 });
      await expect(anonPage.getByTestId('public-profile-display-name')).not.toHaveText(/Journey保母E1/);
      await expect(anonPage.getByTestId('public-profile-plan-item')).not.toBeVisible();
      await expect(anonPage.getByTestId('public-profile-book-now-btn')).not.toBeVisible();
      await testInfo.attach('E-01-未過審保母公開頁被gate', { body: await anonPage.screenshot(), contentType: 'image/png' });
      await anonContext.close();
    });
  });

  test('保母停權後，公開頁被 gate，但停權前已存在的進行中訂單不受影響', async ({ request, browser }, testInfo) => {
    const { sitter, owner, orderId } = await test.step('前置：兜出一筆 CONFIRMED 訂單（此時保母仍為正常 VERIFIED 狀態）', async () => {
      return bootstrapConfirmedOrder(request, 'e2', 'Journey方案E2');
    });

    await test.step('Admin 將此保母停權', async () => {
      const admin = await loginAsSeedAdmin(request);
      const suspendRes = await request.post(`/api/admin/sitters/${sitter.userId}/suspend`, {
        headers: { ...apiAuthed(admin), 'Idempotency-Key': randomUUID() },
        data: { reason: 'Journey E 邊界測試 - 停權' }
      });
      if (!suspendRes.ok()) {
        throw new Error(`suspend failed (${suspendRes.status()}): ${await suspendRes.text()}`);
      }
    });

    await test.step('未登入訪客打開此保母的公開頁，應被 gate（停權跟未過審共用同一套 gating 邏輯）', async () => {
      const anonContext = await browser.newContext();
      const anonPage = await anonContext.newPage();
      await anonPage.goto(`/sitter/${sitter.userId}/profile`);

      await expect(anonPage.getByTestId('public-profile-gated-banner')).toBeVisible({ timeout: 10000 });
      await testInfo.attach('E-02-停權後保母公開頁被gate', { body: await anonPage.screenshot(), contentType: 'image/png' });
      await anonContext.close();
    });

    await test.step('停權前已存在的 CONFIRMED 訂單，Check-in 仍應正常成功（邊界：停權不影響進行中訂單）', async () => {
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

      await awaitOrderStatus(request, owner, orderId, ['IN_PROGRESS']);
      await testInfo.attach('E-03-停權後仍可正常Check-in', {
        body: Buffer.from(JSON.stringify(await startRes.json(), null, 2)),
        contentType: 'application/json'
      });
    });
  });
});
