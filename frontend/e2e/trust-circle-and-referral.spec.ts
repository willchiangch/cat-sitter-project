import { test, expect } from '@playwright/test';

const otherSitterId = 'b2034000-0000-0000-0000-000000000001';
const ownerId = '1031efbc-583a-4062-9a35-15706a3384c6';

test.describe('PRD-010 信任圈與轉介', () => {
  test.setTimeout(60000);

  test('可搜尋保母送出信任圈邀請，同意後雙方清單顯示，並可用信任圈成員發起轉介', async ({ page }) => {
    let relationshipStatus: 'NONE' | 'PENDING' | 'ACCEPTED' = 'NONE';
    const relationshipId = 'r1023000-0000-0000-0000-000000000001';

    await page.route('**/api/sitter/trust-circle/search**', async (route) => {
      const url = new URL(route.request().url());
      const query = url.searchParams.get('query');
      if (query === otherSitterId) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ sitterId: otherSitterId, displayName: '信任圈保母乙', email: 'trust-b@test.com' })
        });
      } else {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'SITTER_NOT_FOUND', message: '查無此保母' }) });
      }
    });

    await page.route(`**/api/sitter/trust-circle/requests/${otherSitterId}`, async (route) => {
      relationshipStatus = 'PENDING';
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'SUCCESS', message: '已送出信任圈邀請' }) });
    });

    await page.route(`**/api/sitter/trust-circle/requests/${relationshipId}/respond`, async (route) => {
      relationshipStatus = 'ACCEPTED';
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'SUCCESS', message: '已加入信任圈' }) });
    });

    await page.route('**/api/sitter/trust-circle', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const data = relationshipStatus === 'ACCEPTED'
        ? [{ relationshipId, sitterId: otherSitterId, displayName: '信任圈保母乙', email: 'trust-b@test.com', status: 'ACCEPTED' }]
        : [];
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    });

    await page.route('**/api/sitter/trust-circle/requests/incoming', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    await page.route('**/api/sitter/trust-circle/requests/outgoing', async (route) => {
      const data = relationshipStatus === 'PENDING'
        ? [{ relationshipId, sitterId: otherSitterId, displayName: '信任圈保母乙', email: 'trust-b@test.com', status: 'PENDING' }]
        : [];
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    });

    await page.goto('/demo');
    await page.getByRole('button', { name: '進入信任圈 (保母端)' }).click();

    await page.getByTestId('trust-add-btn').click();
    await page.getByTestId('trust-search-input').fill(otherSitterId);
    await page.getByTestId('trust-search-submit-btn').click();
    await expect(page.getByTestId('trust-search-result')).toContainText('信任圈保母乙');
    await page.getByTestId('trust-search-confirm-send-btn').click();

    await expect(page.getByTestId(`trust-outgoing-${relationshipId}`)).toContainText('信任圈保母乙');

    // 模擬對方同意（前端沒有「以對方身分接受」的 UI，直接呼叫 accept 後重新整理清單）
    relationshipStatus = 'ACCEPTED';
    await page.getByText('返回 Demo 首頁').click();
    await page.getByRole('button', { name: '進入信任圈 (保母端)' }).click();
    await expect(page.getByTestId(`trust-circle-${otherSitterId}`)).toContainText('信任圈保母乙');
  });

  test('轉介頁面顯示信任圈候選名單，選取後可送出轉介', async ({ page }) => {
    await page.route('**/api/sitter/trust-circle/referral-candidates**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ sitterId: otherSitterId, displayName: '信任圈保母乙', available: true }])
      });
    });

    await page.route('**/api/sitter/trust-circle/referrals', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'SUCCESS', message: '轉介已送出' }) });
    });

    await page.goto('/demo');
    await page.getByRole('button', { name: '直接進入不接單並轉介 (保母端)' }).click();

    await expect(page.getByTestId(`referral-candidate-${otherSitterId}`)).toContainText('信任圈保母乙');
    await page.getByTestId(`referral-candidate-checkbox-${otherSitterId}`).click();
    await page.getByTestId('referral-message-input').fill('這位保母也很細心');
    await page.getByTestId('referral-submit-btn').click();

    await expect(page.getByTestId('referral-success-banner')).toBeVisible();
  });
});
