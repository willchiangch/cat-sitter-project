import { test, expect } from '@playwright/test';

const sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda';

test.describe('PRD-019 我的最愛保母', () => {
  test.setTimeout(60000);

  test('可搜尋保母並加入收藏，切換休息中狀態後清單同步顯示，移除後消失', async ({ page }) => {
    let favorites: any[] = [];

    await page.route('**/api/owner/favorites/search**', async (route) => {
      const url = new URL(route.request().url());
      const query = url.searchParams.get('query');
      if (query === sitterId) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ sitterId, displayName: '測試保母', email: 'sitter@test.com' })
        });
      } else {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'SITTER_NOT_FOUND', message: '查無此保母，請確認 ID 是否正確' })
        });
      }
    });

    await page.route(`**/api/owner/favorites/${sitterId}`, async (route) => {
      if (route.request().method() === 'POST') {
        favorites = [{ sitterId, displayName: '測試保母', avatarUrl: '', tags: ['細心'], removed: false, hidden: false }];
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'SUCCESS', message: '已加入我的最愛' }) });
        return;
      }
      if (route.request().method() === 'DELETE') {
        favorites = [];
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'SUCCESS', message: '已移除收藏' }) });
        return;
      }
      return route.fallback();
    });

    await page.route('**/api/owner/favorites', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(favorites) });
    });

    await page.goto('/demo');
    await page.getByRole('button', { name: '進入我的最愛保母 (飼主端)' }).click();

    await expect(page.locator('text=尚未收藏任何保母')).toBeVisible();

    await page.getByTestId('favorite-add-btn').click();
    await page.getByTestId('favorite-search-input').fill(sitterId);
    await page.getByTestId('favorite-search-submit-btn').click();

    await expect(page.getByTestId('favorite-search-result')).toContainText('測試保母');
    await page.getByTestId('favorite-search-confirm-add-btn').click();

    await expect(page.getByTestId(`favorite-row-${sitterId}`)).toContainText('服務中');

    // 保母切換為休息中 - 用 client-side 導覽離開再回來觸發重新查詢，
    // 避免 page.reload() 造成整頁重載、對同一組種子帳號連續觸發第二次真實登入
    favorites = favorites.map((f) => ({ ...f, hidden: true }));
    await page.getByText('返回 Demo 首頁').click();
    await page.getByRole('button', { name: '進入我的最愛保母 (飼主端)' }).click();
    await expect(page.getByTestId(`favorite-row-${sitterId}`)).toContainText('休息中/隱藏中');

    // 移除收藏
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByTestId(`favorite-remove-${sitterId}`).click();
    await expect(page.locator('text=尚未收藏任何保母')).toBeVisible();
  });

  test('保母公開預約頁可點擊愛心加入/移除收藏', async ({ page }) => {
    let isFavorited = false;

    await page.route(url => url.pathname.includes('/api/auth/login'), async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'fake-token',
          refreshToken: 'fake-refresh',
          userId: '1031efbc-583a-4062-9a35-15706a3384c6',
          email: 'owner@test.com',
          fullName: '愛貓飼主',
          role: 'CLIENT'
        })
      });
    });

    await page.route(url => url.pathname === '/api/pets', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'OK', data: [] }) });
    });

    await page.route(url => url.pathname === `/api/sitter/profile/${sitterId}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          gated: false, sitterId, displayName: '測試保母', avatarUrl: '', bio: '', tags: [],
          serviceAreas: [], isOpen: true, kycStatus: 'VERIFIED', version: 1, isVisible: true
        })
      });
    });

    await page.route(url => url.pathname.includes('/api/sitters/') && url.pathname.endsWith('/plans'), async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'OK', data: [] }) });
    });

    await page.route(url => url.pathname === `/api/sitters/${sitterId}/questions`, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    await page.route('**/api/owner/favorites', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(isFavorited ? [{ sitterId, displayName: '測試保母', removed: false, hidden: false }] : [])
      });
    });

    await page.route(`**/api/owner/favorites/${sitterId}`, async (route) => {
      if (route.request().method() === 'POST') {
        isFavorited = true;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'SUCCESS', message: '已加入我的最愛' }) });
        return;
      }
      if (route.request().method() === 'DELETE') {
        isFavorited = false;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'SUCCESS', message: '已移除收藏' }) });
        return;
      }
      return route.fallback();
    });

    await page.goto('/');
    const roleText = await page.locator('p').filter({ hasText: '當前角色:' }).textContent();
    if (roleText?.includes('貓咪保母')) {
      await page.click('[data-testid="btn-role-toggle"]');
    }
    await page.click('text=進入預約精靈 (飼主端)');

    const heartBtn = page.getByTestId('client-booking-favorite-toggle');
    await expect(heartBtn).toBeVisible();

    await heartBtn.click();
    await expect.poll(() => isFavorited).toBe(true);

    await heartBtn.click();
    await expect.poll(() => isFavorited).toBe(false);
  });
});
