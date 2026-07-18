import { test, expect } from '@playwright/test';

const sitterId = 'a1023000-0000-0000-0000-0000000000aa';

test.describe('PRD-020 內部信用指標管理', () => {
  test.setTimeout(60000);

  test.beforeEach(async ({ page }) => {
    let trustScore = 100;

    await page.route('**/api/admin/sitters/trust-scores', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'OK',
          data: [
            {
              sitterId,
              fullName: '測試保母',
              email: 'sitter@test.com',
              trustScore,
              highRisk: trustScore < 60,
              kycStatus: 'VERIFIED'
            }
          ]
        })
      });
    });

    await page.route(`**/api/admin/sitters/${sitterId}/trust-score/adjust`, async (route) => {
      trustScore -= 50;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: '信用指標已成功更新', data: null })
      });
    });

    await page.getByRole('button', { name: '切換為管理員' }).click();
    await page.getByRole('button', { name: '進入信用指標管理 (管理端)' }).click();
  });

  test('顯示信用指標清單，異動後高風險標籤應出現', async ({ page }) => {
    await expect(page.getByTestId(`trust-row-${sitterId}`)).toContainText('100');
    await expect(page.getByTestId(`trust-high-risk-${sitterId}`)).toHaveCount(0);

    await page.getByTestId(`trust-select-${sitterId}`).click();
    await expect(page.getByTestId('trust-adjust-sitter-id')).toHaveValue(sitterId);

    await page.getByTestId('trust-adjust-delta').fill('-50');
    await page.getByTestId('trust-adjust-reason').fill('訂單爭議判賠');
    await page.getByTestId('trust-adjust-submit-btn').click();

    await expect(page.getByTestId('trust-adjust-message')).toContainText('信用指標已成功更新');
    await expect(page.getByTestId(`trust-row-${sitterId}`)).toContainText('50');
    await expect(page.getByTestId(`trust-high-risk-${sitterId}`)).toBeVisible();
  });
});
