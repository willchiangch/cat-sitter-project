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
    const targetDate1 = new Date(today); targetDate1.setDate(today.getDate() + 1);
    const targetDate2 = new Date(today); targetDate2.setDate(today.getDate() + 2);
    const dateStr1 = targetDate1.toISOString().split('T')[0];
    const dateStr2 = targetDate2.toISOString().split('T')[0];

    await test.step('選擇方案與日期', async () => {
      await expect(page.locator('h2')).toContainText('排程配置');
      
      // 點擊卡片選擇進階陪伴 (p2)
      await page.click('[data-testid="client-booking-plan-card-p2"]');
      
      // 選擇兩天日期
      await page.waitForSelector(`[data-testid="client-booking-date-0-0-${dateStr1}"]`);
      await page.click(`[data-testid="client-booking-date-0-0-${dateStr1}"]`);
      await page.click(`[data-testid="client-booking-date-0-0-${dateStr2}"]`);
      
      // 選擇趟次
      await page.selectOption('[data-testid="client-booking-times-0-0"]', '2');
      
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 2: 方案配置完成', { body: screenshot, contentType: 'image/png' });
      await page.click('[data-testid="client-booking-btn-step1-next"]');
    });

    await test.step('確認預約摘要', async () => {
      await expect(page.locator('h2')).toContainText('預約摘要');
      // p2 (800) * 2 days * 2 times = 3200
      await expect(page.locator('[data-testid="client-booking-total"]')).toContainText('$ 3,200');
      const screenshot = await page.screenshot();
      await testInfo.attach('步驟 3: 摘要確認', { body: screenshot, contentType: 'image/png' });
      await page.click('[data-testid="client-booking-btn-submit"]');
    });
  });

  test('TS-005-02 【飼主端】複合式預約案例驗證 (多方案、多排程)', async ({ page }, testInfo) => {
    await test.step('初始化頁面', async () => {
      const roleText = await page.locator('p').filter({ hasText: '當前角色:' }).textContent();
      if (roleText?.includes('貓咪保母')) {
        await page.click('[data-testid="btn-role-toggle"]');
      }
      await page.click('text=進入預約精靈 (飼主端)');
    });

    const dates: string[] = [];
    for (let i = 1; i <= 5; i++) {
      const d = new Date();
      d.setDate(d.getDate() + i);
      dates.push(d.toISOString().split('T')[0]);
    }

    await test.step('配置複合方案排程矩陣', async () => {
      // 方案 1: 基礎照顧 (p1)
      await page.click('[data-testid="client-booking-plan-card-p1"]');
      // 日期 1, 2
      await page.click(`[data-testid="client-booking-date-0-0-${dates[0]}"]`);
      await page.click(`[data-testid="client-booking-date-0-0-${dates[1]}"]`);
      await page.selectOption('[data-testid="client-booking-times-0-0"]', '2'); // 500 * 2 * 2 = 2000

      // 新增第二個方案
      await page.click('[data-testid="client-booking-btn-add-plan"]');
      await testInfo.attach('2-06-新增方案', { body: await page.screenshot(), contentType: 'image/png' });
      
      // 方案 2: 進階陪伴 (p2)
      await page.click('[data-testid="client-booking-plan-card-p2"]');
      await testInfo.attach('2-07-選擇進階陪伴', { body: await page.screenshot(), contentType: 'image/png' });

      // 排程 1: 日期 3, 5
      await page.click(`[data-testid="client-booking-date-1-0-${dates[2]}"]`);
      await testInfo.attach('2-08-日期3', { body: await page.screenshot(), contentType: 'image/png' });

      await page.click(`[data-testid="client-booking-date-1-0-${dates[4]}"]`);
      await testInfo.attach('2-09-日期5', { body: await page.screenshot(), contentType: 'image/png' });

      await page.selectOption('[data-testid="client-booking-times-1-0"]', '1');
      await testInfo.attach('2-10-進階趟次', { body: await page.screenshot(), contentType: 'image/png' });

      // 方案 2 的 排程 2: 日期 4
      await page.click('[data-testid="client-booking-btn-add-schedule-1"]');
      await testInfo.attach('2-11-新增排程', { body: await page.screenshot(), contentType: 'image/png' });

      await page.click(`[data-testid="client-booking-date-1-1-${dates[3]}"]`);
      await testInfo.attach('2-12-日期4', { body: await page.screenshot(), contentType: 'image/png' });

      await page.selectOption('[data-testid="client-booking-times-1-1"]', '2');
      await testInfo.attach('2-13-額外趟次', { body: await page.screenshot(), contentType: 'image/png' });

      await page.click('[data-testid="client-booking-btn-step1-next"]');
      await testInfo.attach('2-14-進入摘要', { body: await page.screenshot(), contentType: 'image/png' });
    });

    await test.step('確認最終摘要金額', async () => {
      await expect(page.locator('h2')).toContainText('預約摘要');
      await expect(page.locator('[data-testid="client-booking-total"]')).toContainText('$ 5,200');
      await testInfo.attach('2-15-最終確認', { body: await page.screenshot(), contentType: 'image/png' });

      await page.click('[data-testid="client-booking-btn-submit"]');
      await testInfo.attach('2-16-送出', { body: await page.screenshot(), contentType: 'image/png' });
    });
  });

});
