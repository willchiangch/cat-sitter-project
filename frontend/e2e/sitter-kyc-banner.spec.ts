import path from 'path';
import { fileURLToPath } from 'url';
import { test, expect } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession, serveFixtureImage } from './support/mockSession';

const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), 'journeys/fixtures');

/**
 * Backlog 第 3 批 U3：保母送出 KYC 後頁面徽章已是「審核中」，AppHeader 的橫幅卻還掛著送出前抓的
 * 「尚未完成保母實名認證」。橫幅與頁面改讀同一份 React Query 快取 (src/hooks/useKyc.ts)。
 */
test.describe('保母 KYC 頁橫幅依 kycStatus 顯示 (U3)', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('送出 KYC 後，頁面徽章與頂部橫幅同時變成審核中', async ({ page }) => {
    await mockDemoSession(page, 'sitter');
    let kycStatus = 'UNVERIFIED';
    await page.route('**/api/sitter/kyc/status', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            kycStatus,
            rejectReason: '',
            submittedAt: kycStatus === 'UNVERIFIED' ? '' : '2026-10-24T00:00:00Z'
          }
        })
      })
    );
    await page.route('**/api/sitter/kyc', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      kycStatus = 'PENDING_REVIEW';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { recordId: 'r-1', status: 'PENDING' } })
      });
    });
    const mediaUrl = 'https://storage.example.test/kyc/sitter-media.jpg?X-Goog-Signature=mock';
    await page.route('**/api/sitter/kyc/media/*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { signedUrl: mediaUrl } })
      })
    );
    await serveFixtureImage(page, mediaUrl, 'kyc-selfie.jpg');

    const badge = page.getByTestId('sitter-kyc-status-badge');
    const banner = page.getByTestId('sitter-kyc-banner');

    await step('未認證：徽章「未認證」、橫幅提示尚未完成認證', page, async () => {
      await page.goto('/sitter/kyc');
      await expect(badge).toHaveText('未認證');
      await expect(banner).toContainText('尚未完成保母實名認證');
    });

    await step('送出 KYC：徽章與橫幅都變成審核中，不再提示尚未完成認證', page, async () => {
      await page
        .getByTestId('sitter-kyc-id-front-input')
        .setInputFiles(path.join(FIXTURES, 'kyc-id-front.jpg'));
      await page
        .getByTestId('sitter-kyc-selfie-input')
        .setInputFiles(path.join(FIXTURES, 'kyc-selfie.jpg'));
      await page.getByTestId('sitter-kyc-submit-btn').click();
      await expect(badge).toHaveText('審核中');
      await expect(banner).toContainText('認證資料正在審核中');
      await expect(banner).not.toContainText('尚未完成');
      await expect(page).toHaveURL(/\/sitter\/kyc$/);
    });
  });

  test('KYC 狀態 API 失敗時不顯示橫幅（不再一律當成未認證）', async ({ page }) => {
    await mockDemoSession(page, 'sitter');
    await page.route('**/api/sitter/kyc/status', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'boom' })
      })
    );
    await step('開 Demo 首頁：沒有 KYC 橫幅', page, async () => {
      const statusRes = page.waitForResponse((r) => r.url().includes('/api/sitter/kyc/status'));
      await page.goto('/demo');
      expect((await statusRes).status()).toBe(500);
      await expect(page.getByText('當前角色:')).toContainText('貓咪保母');
      await expect(page.getByTestId('sitter-kyc-banner')).toHaveCount(0);
    });
  });
});
