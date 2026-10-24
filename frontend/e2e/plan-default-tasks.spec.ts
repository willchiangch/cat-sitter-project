import { test, expect, type Page } from '@playwright/test';
import { step, apiStep } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession } from './support/mockSession';

/**
 * Backlog 第 3 批 U6（驗收 N1）：新增方案的預設 SOP 不分寵物一律是「基本餵食 / 清理砂盆 / 更換新鮮水」，
 * 狗狗方案也帶著「清理砂盆」。改為依適用寵物給模板（src/components/plans/defaultTasks.ts）。
 */
const taskValues = (page: Page) =>
  page
    .locator('[data-testid^="sitter-plan-input-task-"]')
    .evaluateAll((els) => els.map((el) => (el as HTMLInputElement).value));

/**
 * 驗收 R2：表單在 modal 內自己捲動，step() 的整頁截圖只拍得到 modal 上半部，「預設照護 SOP 工作項目」以下全被裁掉。
 * 截圖前把任務區標題捲到 modal 可視區中央：上方仍看得到「適用寵物類型」的勾選狀態，下方是整串任務。
 */
const showTasks = (page: Page) =>
  page
    .getByTestId('sitter-plan-btn-add-task')
    .evaluate((el) => el.scrollIntoView({ block: 'center' }));

test.describe('方案預設任務依適用寵物給 (U6)', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test('新增方案：CAT→DOG 換成狗狗模板；手動改過任務後不再被覆蓋；送出的 defaultTasks 不含清理砂盆', async ({
    page
  }) => {
    await mockDemoSession(page, 'sitter');
    const created: { name: string; defaultTasks: string[]; applicablePetTypes: string[] }[] = [];
    // 有狀態的 mock：POST 之後 GET 要回傳新方案。過去 GET 永遠回 []，送出後列表 invalidate 重抓，
    // 截圖停在「目前尚無任何服務方案」（驗收 R2：mock 缺口，不是時序）
    const plans: object[] = [];
    await page.route('**/api/sitter/plans', async (route) => {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON();
        created.push(body);
        const plan = { ...body, id: 'plan-dog', version: 0, sortOrder: 0, isActive: true };
        plans.push(plan);
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: plan })
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: plans })
      });
    });

    await step('開新增方案：預設勾貓咪，任務為貓咪模板', page, async () => {
      await page.goto('/sitter/plans');
      await page.getByTestId('sitter-plan-btn-add').click();
      await expect(page.getByTestId('sitter-plan-input-task-0')).toBeVisible();
      expect(await taskValues(page)).toEqual(['基本餵食', '清理砂盆', '更換新鮮水']);
      await showTasks(page);
    });

    await step('改成只勾狗狗：任務換成狗狗模板，沒有清理砂盆', page, async () => {
      await page.getByTestId('sitter-plan-checkbox-pet-CAT').click();
      await page.getByTestId('sitter-plan-checkbox-pet-DOG').click();
      await expect(page.getByTestId('sitter-plan-input-task-1')).toHaveValue('散步遛狗');
      const tasks = await taskValues(page);
      expect(tasks).toEqual(['基本餵食', '散步遛狗', '清理排泄物', '更換新鮮水']);
      expect(tasks).not.toContain('清理砂盆');
      await showTasks(page);
    });

    await step('再加勾貓咪：取聯集', page, async () => {
      await page.getByTestId('sitter-plan-checkbox-pet-CAT').click();
      await expect(page.getByTestId('sitter-plan-input-task-4')).toHaveValue('清理砂盆');
      expect(await taskValues(page)).toEqual([
        '基本餵食',
        '散步遛狗',
        '清理排泄物',
        '更換新鮮水',
        '清理砂盆'
      ]);
      await showTasks(page);
    });

    await step('手動改任務後再切換寵物：保留手動內容', page, async () => {
      await page.getByTestId('sitter-plan-checkbox-pet-CAT').click();
      await expect(page.getByTestId('sitter-plan-input-task-4')).toHaveCount(0);
      await page.getByTestId('sitter-plan-input-task-0').fill('餵食（乾糧 1 杯）');
      await page.getByTestId('sitter-plan-checkbox-pet-BIRD').click();
      await expect(page.getByTestId('sitter-plan-input-task-0')).toHaveValue('餵食（乾糧 1 杯）');
      await page.getByTestId('sitter-plan-checkbox-pet-BIRD').click();
      expect(await taskValues(page)).toEqual([
        '餵食（乾糧 1 杯）',
        '散步遛狗',
        '清理排泄物',
        '更換新鮮水'
      ]);
      await showTasks(page);
    });

    await step('送出：modal 關閉，列表出現新方案卡片', page, async () => {
      await page.getByTestId('sitter-plan-input-name').fill('狗狗到府');
      await page.getByTestId('sitter-plan-input-price').fill('900');
      await expect(page.getByTestId('sitter-plan-input-capacity')).toHaveCount(0);
      await page.getByTestId('sitter-plan-input-duration').fill('60');
      await page.getByTestId('sitter-plan-btn-save').click();
      await expect.poll(() => created.length).toBe(1);
      await expect(page.getByTestId('sitter-plan-input-task-0')).toHaveCount(0);
      await expect(page.getByTestId('sitter-plan-card-plan-dog')).toContainText('狗狗到府');
      await expect(page.getByText('目前尚無任何服務方案')).toHaveCount(0);
    });

    await apiStep(
      '送出的 POST /api/sitter/plans body：DOG 方案、defaultTasks 為狗狗任務、不帶 dailyCapacity',
      async () => {
        expect(created).toHaveLength(1);
        expect(created[0].applicablePetTypes).toEqual(['DOG']);
        expect(created[0].defaultTasks).toEqual([
          '餵食（乾糧 1 杯）',
          '散步遛狗',
          '清理排泄物',
          '更換新鮮水'
        ]);
        expect(created[0].defaultTasks).not.toContain('清理砂盆');
        expect(created[0]).not.toHaveProperty('dailyCapacity');
        return created[0];
      }
    );
  });
});
