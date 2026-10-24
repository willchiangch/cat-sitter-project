import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession, serveFixtureImage } from './support/mockSession';
import { expectStandardPageInset } from './support/layout';

/**
 * Backlog 第 3 批 U1：AdminKycDetail 在 390px 的 AppShell 裡溢出，證件照/自拍被裁掉，管理員看不到要審的照片。
 */
const recordId = 'b1000000-0000-0000-0000-00000000c0de';
const sitterId = 'b2000000-0000-0000-0000-00000000c0de';
const FRONT_URL = 'https://storage.example.test/kyc/id-front.jpg?X-Goog-Signature=mock';
const SELFIE_URL = 'https://storage.example.test/kyc/selfie.jpg?X-Goog-Signature=mock';

async function mockKycDetail(page: Page) {
  await mockDemoSession(page, 'admin');
  await page.route(`**/api/admin/kyc/${recordId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          recordId,
          sitterId,
          fullName: 'KYC 版面測試保母',
          email: 'kyc-layout@example.test',
          kycStatus: 'PENDING',
          submittedAt: '2026-10-24T00:00:00Z',
          idCardFrontKey: 'kyc/front',
          selfieKey: 'kyc/selfie'
        }
      })
    })
  );
  await page.route(`**/api/admin/kyc/${sitterId}/media/*`, (route) => {
    const type = route.request().url().split('/').pop();
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { signedUrl: type === 'selfie' ? SELFIE_URL : FRONT_URL } })
    });
  });
  await serveFixtureImage(page, FRONT_URL, 'kyc-id-front.jpg');
  await serveFixtureImage(page, SELFIE_URL, 'kyc-selfie.jpg');
}

test.describe('管理端 KYC 審核詳情 (U1/U2)', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('U1：證件照與自拍完整顯示在 AppShell 可視範圍內，不被裁切', async ({ page }) => {
    await mockKycDetail(page);

    await step('開 KYC 審核詳情頁', page, async () => {
      await page.goto(`/admin/kyc/${recordId}`);
      await expect(page.getByText('KYC 版面測試保母')).toBeVisible();
    });

    await step('兩張照片都已載入，寬度足夠、完全落在 <main> 的左右邊界內', page, async () => {
      const main = await page.locator('main').boundingBox();
      expect(main).not.toBeNull();
      for (const testId of ['admin-kyc-detail-id-front-img', 'admin-kyc-detail-selfie-img']) {
        const img = page.getByTestId(testId);
        await expect(img).toBeVisible();
        await expect
          .poll(() => img.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0))
          .toBe(true);
        const box = await img.boundingBox();
        expect(box, testId).not.toBeNull();
        // 390px 行動版內容寬約 294px：照片至少 250px 寬才看得清楚
        expect(box!.width, `${testId} 寬度`).toBeGreaterThan(250);
        expect(box!.x, `${testId} 左緣`).toBeGreaterThanOrEqual(main!.x);
        expect(box!.x + box!.width, `${testId} 右緣`).toBeLessThanOrEqual(main!.x + main!.width);
      }
      // 審核按鈕也要在可視寬度內
      const approve = await page.getByTestId('admin-kyc-detail-approve-btn').boundingBox();
      expect(approve!.x + approve!.width).toBeLessThanOrEqual(main!.x + main!.width);
      // 驗收 N17：頁面容器過去是 '1.5rem 0'，內容比其他頁左右各寬 24px；改回標準內距後照片仍 > 250px
      await expectStandardPageInset(page, 'KYC 審核詳情頁');
    });
  });

  test('U2：核准成功後、導回清單前，徽章已是「已通過」且審核按鈕鎖住', async ({ page }) => {
    await mockKycDetail(page);
    const reviews: unknown[] = [];
    await page.route(`**/api/admin/kyc/${recordId}/review`, async (route) => {
      reviews.push(route.request().postDataJSON());
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{"data":null}' });
    });
    await page.route('**/api/admin/kyc/pending**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }
        })
      })
    );

    await step('開詳情頁：徽章為待審核', page, async () => {
      await page.goto(`/admin/kyc/${recordId}`);
      await expect(page.getByTestId('admin-kyc-detail-status-badge')).toHaveText(/^待審核/);
    });

    await step('點批准：成功訊息出現時徽章已是已通過、仍在詳情頁', page, async () => {
      await page.getByTestId('admin-kyc-detail-approve-btn').click();
      await expect(page.getByText('實名認證審核已通過！')).toBeVisible();
      await expect(page.getByTestId('admin-kyc-detail-status-badge')).toHaveText(/^已通過/);
      await expect(page).toHaveURL(new RegExp(`/admin/kyc/${recordId}$`));
      await expect(page.getByTestId('admin-kyc-detail-approve-btn')).toBeDisabled();
      // N2：停用要看得出來（過去 .btn-primary 沒有 :disabled 樣式，外觀與可按時完全相同）
      for (const testId of ['admin-kyc-detail-approve-btn', 'admin-kyc-detail-reject-btn']) {
        const btn = page.getByTestId(testId);
        await expect(btn).toBeDisabled();
        await expect(btn).toHaveCSS('opacity', '0.45');
        await expect(btn).toHaveCSS('cursor', 'not-allowed');
      }
      expect(reviews).toEqual([{ action: 'APPROVE', rejectReason: null }]);
    });

    await step('之後導回待審清單', page, async () => {
      await expect(page).toHaveURL(/\/admin\/kyc$/);
    });
  });
});
