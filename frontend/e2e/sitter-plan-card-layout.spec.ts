import { test, expect } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession } from './support/mockSession';

// N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
test.afterEach(({ page }) => assertNoUnmockedApi(page));

/**
 * Backlog 第 3 批 U7（驗收 N2）：方案名稱含長串英數（Journey 的時間戳）時，卡片左欄不肯縮，
 * 右側「$ 600 / 60 分鐘」被擠出卡片右邊界。
 */
const plans = [
  { id: 'plan-cat', name: 'Journey方案A-常態照護-1792780917874', price: 600, pet: 'CAT' },
  { id: 'plan-dog', name: 'Journey方案A-陪伴散步-1792780917874', price: 900, pet: 'DOG' }
].map((p, i) => ({
  id: p.id,
  sitterId: 's-1',
  name: p.name,
  price: p.price,
  durationMinutes: 60,
  defaultTasks: ['基本餵食'],
  applicablePetTypes: [p.pet],
  description: '',
  startDate: null,
  endDate: null,
  isRestricted: false,
  isActive: true,
  sortOrder: i,
  version: 0
}));

test('U7：方案卡價格完整落在卡片內，長方案名稱自行換行', async ({ page }) => {
  await mockDemoSession(page, 'sitter');
  await page.route('**/api/sitter/plans', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: plans })
    })
  );

  await step('開方案設定頁，逐張檢查價格區塊的邊界', page, async () => {
    await page.goto('/sitter/plans');
    for (const plan of plans) {
      const card = page.getByTestId(`sitter-plan-card-${plan.id}`);
      const price = page.getByTestId(`sitter-plan-price-${plan.id}`);
      await expect(card).toContainText(plan.name);
      // 系統不做每日容量控管（2026-10 移除 dailyCapacity）：卡片不再顯示「限額 N 次/日」
      await expect(card).not.toContainText('次/日');
      // fix-batch12 U2：formatMoney 的「$」後面是不斷行空白 U+00A0（regex 不做空白正規化）
      await expect(price).toHaveText(new RegExp(`^\\$\\u00a0${plan.price}\\s*/ 60 分鐘$`));
      const cardBox = (await card.boundingBox())!;
      const priceBox = (await price.boundingBox())!;
      expect(priceBox.x + priceBox.width, `${plan.id} 價格右緣`).toBeLessThanOrEqual(
        cardBox.x + cardBox.width
      );
      expect(priceBox.x, `${plan.id} 價格左緣`).toBeGreaterThan(cardBox.x);
      // 卡片內容不得水平溢出（溢出時 scrollWidth 會大於 clientWidth）
      expect(await card.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    }
  });
});
