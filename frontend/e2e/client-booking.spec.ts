import { test, expect } from '@playwright/test';

test.describe('TS-005 飼主預約精靈流程', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('TS-005-01 【飼主端】基礎預約流程驗證 (單一項目)', async ({ page }, testInfo) => {
    await test.step('進入預約精靈並確認角色', async () => {
      const roleText = await page.locator('p').filter({ hasText: '當前角色:' }).textContent();
      if (roleText?.includes('貓咪保母')) {
        await page.click('[data-testid="btn-role-toggle"]');
      }
      await page.click('text=進入預約精靈 (飼主端)');
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 1: 進入頁面', { body: screenshot, contentType: 'image/png' });
    });

    const today = new Date();
    const targetDate = new Date(today);
    targetDate.setDate(today.getDate() + 1);
    const dateStr = targetDate.toISOString().split('T')[0];

    await test.step('選擇日期', async () => {
      await page.waitForSelector(`[data-testid="client-booking-date-${dateStr}"]`);
      await page.click(`[data-testid="client-booking-date-${dateStr}"]`);
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 2: 已選取日期', { body: screenshot, contentType: 'image/png' });
      await page.click('[data-testid="client-booking-btn-step1-next"]');
    });

    await test.step('配置方案與毛孩', async () => {
      await expect(page.locator('h2')).toContainText('預約方案配置');
      await page.selectOption('[data-testid="client-booking-item-0-plan-select"]', 'p2');
      await page.selectOption('[data-testid="client-booking-item-0-times-select"]', '2');
      await page.click('[data-testid="client-booking-pet-pet1"]');
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 3: 方案配置完成', { body: screenshot, contentType: 'image/png' });
      await page.click('[data-testid="client-booking-btn-step2-next"]');
    });

    await test.step('確認預約摘要', async () => {
      await expect(page.locator('h2')).toContainText('預約摘要');
      await expect(page.locator('[data-testid="client-booking-total"]')).toContainText('$ 1,600');
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 4: 摘要確認', { body: screenshot, contentType: 'image/png' });
      await page.click('[data-testid="client-booking-btn-submit"]');
    });
  });

  test('TS-005-02 【飼主端】複合式預約案例驗證 (多日期、多方案、多趟次)', async ({ page }, testInfo) => {
    await test.step('初始化頁面', async () => {
      const roleText = await page.locator('p').filter({ hasText: '當前角色:' }).textContent();
      if (roleText?.includes('貓咪保母')) {
        await page.click('[data-testid="btn-role-toggle"]');
      }
      await page.click('text=進入預約精靈 (飼主端)');
    });

    const dates: string[] = [];
    await test.step('選擇多個日期', async () => {
      for (let i = 1; i <= 5; i++) {
        const d = new Date();
        d.setDate(d.getDate() + i);
        const s = d.toISOString().split('T')[0];
        dates.push(s);
        await page.waitForSelector(`[data-testid="client-booking-date-${s}"]`);
        await page.click(`[data-testid="client-booking-date-${s}"]`);
      }
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 1: 已選取多日', { body: screenshot, contentType: 'image/png' });
      await page.click('[data-testid="client-booking-btn-step1-next"]');
    });

    await test.step('配置複合排程矩陣', async () => {
      await expect(page.locator('h2')).toContainText('預約方案配置');
      
      // Item 1
      await page.click(`[data-testid="client-booking-item-0-date-${dates[3]}"]`);
      await page.selectOption('[data-testid="client-booking-item-0-plan-select"]', 'p1');
      await page.selectOption('[data-testid="client-booking-item-0-times-select"]', '2');

      // Item 2
      await page.click('[data-testid="client-booking-btn-add-item"]');
      await page.click(`[data-testid="client-booking-item-1-date-${dates[3]}"]`);
      await page.selectOption('[data-testid="client-booking-item-1-plan-select"]', 'p2');
      await page.selectOption('[data-testid="client-booking-item-1-times-select"]', '1');

      // Item 3
      await page.click('[data-testid="client-booking-btn-add-item"]');
      await page.click(`[data-testid="client-booking-item-2-date-${dates[0]}"]`);
      await page.click(`[data-testid="client-booking-item-2-date-${dates[1]}"]`);
      await page.click(`[data-testid="client-booking-item-2-date-${dates[2]}"]`);
      await page.click(`[data-testid="client-booking-item-2-date-${dates[4]}"]`);
      await page.selectOption('[data-testid="client-booking-item-2-plan-select"]', 'p2');
      await page.selectOption('[data-testid="client-booking-item-2-times-select"]', '2');

      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 2: 複合方案配置完成', { body: screenshot, contentType: 'image/png' });
      
      await page.click('[data-testid="client-booking-pet-pet1"]');
      await page.click('[data-testid="client-booking-btn-step2-next"]');
    });

    await test.step('確認最終摘要金額', async () => {
      await expect(page.locator('h2')).toContainText('預約摘要');
      await expect(page.locator('[data-testid="client-booking-total"]')).toContainText('$ 8,800');
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 3: 最終摘要確認', { body: screenshot, contentType: 'image/png' });
      await page.click('[data-testid="client-booking-btn-submit"]');
    });
  });

});
