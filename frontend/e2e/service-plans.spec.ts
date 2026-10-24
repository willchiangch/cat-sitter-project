import { test, expect, type Page, type Route } from '@playwright/test';
import { step, apiStep, trackDialogs, expectDialog } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession } from './support/mockSession';

/**
 * SD-003 保母服務方案設定（TS-003）。
 * 證據稽核（.e2e-runs/20261024-1939）：過去整支只有 1 個 step()，4 個 test 完全沒有截圖，
 * beforeEach 自己寫 accessToken 而非共用 mockDemoSession，漏 mock 的 API 會帶假 token 打到正式站。
 */
type Plan = {
  id: string;
  sitterId: string;
  name: string;
  price: number;
  defaultTasks: string[];
  applicablePetTypes: string[];
  description?: string;
  startDate?: string | null;
  endDate?: string | null;
  isRestricted: boolean;
  isActive?: boolean;
  sortOrder: number;
  version: number;
};

const basePlan = (overrides: Partial<Plan> = {}): Plan => ({
  id: 'plan-1',
  sitterId: 'sitter-123',
  name: '基礎照護',
  price: 500,
  defaultTasks: ['基本餵食', '清理砂盆'],
  applicablePetTypes: ['CAT'],
  description: '標準貓咪照顧',
  startDate: null,
  endDate: null,
  isRestricted: false,
  sortOrder: 0,
  version: 0,
  ...overrides
});

const ok = (route: Route, data: unknown, message = 'OK') =>
  route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message, data })
  });

/** GET 回傳目前的 plans（有狀態：寫入後重抓要看得到新資料）；POST 交給 onPost，沒給就 fallback 到 catch-all */
async function mockPlanList(
  page: Page,
  getPlans: () => Plan[],
  onPost?: (route: Route) => Promise<void>
) {
  await page.route('**/api/sitter/plans', async (route) => {
    if (route.request().method() === 'GET') return ok(route, getPlans());
    if (route.request().method() === 'POST' && onPost) return onPost(route);
    return route.fallback();
  });
}

async function openPlansFromDemo(page: Page) {
  await page.goto('/demo');
  await page.getByTestId('btn-go-sitter-plans').click();
  await expect(page).toHaveURL(/\/sitter\/plans$/);
}

test.describe('SD-003 保母服務方案設定 E2E 測試', () => {
  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  test.beforeEach(async ({ page }) => {
    await mockDemoSession(page, 'sitter');
  });

  test('保母成功查詢方案列表與新增常態方案', async ({ page }) => {
    const mockPlans: Plan[] = [basePlan()];
    const createdBodies: Record<string, unknown>[] = [];
    await mockPlanList(
      page,
      () => mockPlans,
      async (route) => {
        const body = route.request().postDataJSON();
        createdBodies.push(body);
        const newPlan = { ...body, id: 'plan-2', version: 0, sortOrder: mockPlans.length };
        mockPlans.push(newPlan);
        await ok(route, newPlan, '新增成功');
      }
    );

    await step('從 Demo 首頁進入方案設定：既有方案卡片', page, async () => {
      await openPlansFromDemo(page);
      const card = page.getByTestId('sitter-plan-card-plan-1');
      await expect(card).toBeVisible();
      await expect(card).toContainText('基礎照護');
      await expect(page.getByTestId('sitter-plan-price-plan-1')).toContainText('$ 500');
    });

    await step('新增方案表單沒有每日容量欄位（系統不做容量控管）', page, async () => {
      await page.getByTestId('sitter-plan-btn-add').click();
      await page.getByTestId('sitter-plan-input-name').fill('暑假豪華陪玩');
      await page.getByTestId('sitter-plan-input-price').fill('1200');
      await expect(page.getByTestId('sitter-plan-input-price')).toHaveValue('1200');
      await expect(page.getByTestId('sitter-plan-input-capacity')).toHaveCount(0);
      await expect(page.getByText('每日接單容量限制')).toHaveCount(0);
    });

    await step('填寫適用寵物、說明與 SOP 任務', page, async () => {
      // 貓咪是預選，再加勾狗狗
      await page.getByTestId('sitter-plan-checkbox-pet-DOG').click();
      // 寵物選項是 toggle button（不是 checkbox）：以任務模板換成貓狗聯集判斷已勾上狗狗
      await expect
        .poll(() =>
          page
            .locator('[data-testid^="sitter-plan-input-task-"]')
            .evaluateAll((els) => els.map((el) => (el as HTMLInputElement).value))
        )
        .toContain('散步遛狗');
      await page.getByTestId('sitter-plan-input-description').fill('超長陪伴，包含梳毛與陪玩');
      await page.getByTestId('sitter-plan-input-task-0').fill('梳毛 15 分鐘');
      await expect(page.getByTestId('sitter-plan-input-task-0')).toHaveValue('梳毛 15 分鐘');
    });

    await step('儲存後清單出現新方案「暑假豪華陪玩 / $ 1200」', page, async () => {
      await page.getByTestId('sitter-plan-btn-save').click();
      await expect.poll(() => createdBodies.length).toBe(1);
      const card = page.getByTestId('sitter-plan-card-plan-2');
      await expect(card).toBeVisible();
      await expect(card).toContainText('暑假豪華陪玩');
      await expect(page.getByTestId('sitter-plan-price-plan-2')).toContainText('$ 1200');
      await expect(page.getByTestId('sitter-plan-input-name')).toHaveCount(0);
    });

    await apiStep('送出的 POST /api/sitter/plans body 不帶 dailyCapacity', async () => {
      expect(createdBodies).toHaveLength(1);
      expect(createdBodies[0]).toMatchObject({ name: '暑假豪華陪玩', price: 1200 });
      expect(createdBodies[0]).not.toHaveProperty('dailyCapacity');
      return createdBodies[0];
    });
  });

  test('保母編輯方案與預設任務動態刪減', async ({ page }) => {
    const mockPlans: Plan[] = [basePlan()];
    const putBodies: Record<string, unknown>[] = [];
    await mockPlanList(page, () => mockPlans);
    await page.route('**/api/sitter/plans/plan-1', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      const body = route.request().postDataJSON();
      putBodies.push(body);
      mockPlans[0] = { ...mockPlans[0], ...body, version: 1 };
      await ok(route, mockPlans[0], '修改成功');
    });

    // 驗收 R5：表單在 modal 內自己捲動，名稱價格與任務區塞不進同一個 viewport，
    // 拆成兩張：每張截圖前把對應欄位捲進畫面並斷言 toBeInViewport
    await step('開啟編輯：表單帶入原方案名稱與價格', page, async () => {
      await openPlansFromDemo(page);
      await page.getByTestId('sitter-plan-btn-edit-plan-1').click();
      await expect(page.getByTestId('sitter-plan-input-name')).toHaveValue('基礎照護');
      await expect(page.getByTestId('sitter-plan-input-price')).toHaveValue('500');
      // 價格欄置中：名稱欄（含標題）與價格欄都在 modal 可視區，不會被 modal 圓角頂端裁掉
      await page
        .getByTestId('sitter-plan-input-price')
        .evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await expect(page.getByTestId('sitter-plan-input-name')).toBeInViewport();
      await expect(page.getByTestId('sitter-plan-input-price')).toBeInViewport();
    });

    await step('編輯表單帶入兩項預設任務「基本餵食」「清理砂盆」', page, async () => {
      await page.getByTestId('sitter-plan-input-task-1').scrollIntoViewIfNeeded();
      await expect(page.getByTestId('sitter-plan-input-task-0')).toHaveValue('基本餵食');
      await expect(page.getByTestId('sitter-plan-input-task-1')).toHaveValue('清理砂盆');
      await expect(page.getByTestId('sitter-plan-input-task-0')).toBeInViewport();
      await expect(page.getByTestId('sitter-plan-input-task-1')).toBeInViewport();
    });

    await step('改名稱「基礎照護 (新版)」、價格 600', page, async () => {
      await page.getByTestId('sitter-plan-input-name').fill('基礎照護 (新版)');
      await page.getByTestId('sitter-plan-input-price').fill('600');
      // 價格欄置中：名稱欄（含標題）與價格欄都在 modal 可視區，不會被 modal 圓角頂端裁掉
      await page
        .getByTestId('sitter-plan-input-price')
        .evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await expect(page.getByTestId('sitter-plan-input-name')).toHaveValue('基礎照護 (新版)');
      await expect(page.getByTestId('sitter-plan-input-price')).toHaveValue('600');
      await expect(page.getByTestId('sitter-plan-input-name')).toBeInViewport();
      await expect(page.getByTestId('sitter-plan-input-price')).toBeInViewport();
    });

    await step('刪除「清理砂盆」、新增「拍照 5 張」', page, async () => {
      await page.getByTestId('sitter-plan-btn-delete-task-1').click();
      await expect(page.getByTestId('sitter-plan-input-task-1')).toHaveCount(0);
      await page.getByTestId('sitter-plan-btn-add-task').click();
      await page.getByTestId('sitter-plan-input-task-1').fill('拍照 5 張');
      await page.getByTestId('sitter-plan-input-task-1').scrollIntoViewIfNeeded();
      await expect(page.getByTestId('sitter-plan-input-task-0')).toHaveValue('基本餵食');
      await expect(page.getByTestId('sitter-plan-input-task-1')).toHaveValue('拍照 5 張');
      await expect(page.getByTestId('sitter-plan-input-task-0')).toBeInViewport();
      await expect(page.getByTestId('sitter-plan-input-task-1')).toBeInViewport();
    });

    await step('儲存後卡片顯示新名稱、新價格與新任務', page, async () => {
      await page.getByTestId('sitter-plan-btn-save').click();
      await expect.poll(() => putBodies.length).toBe(1);
      const card = page.getByTestId('sitter-plan-card-plan-1');
      await expect(card).toContainText('基礎照護 (新版)');
      await expect(page.getByTestId('sitter-plan-price-plan-1')).toContainText('$ 600');
      await expect(card).toContainText('拍照 5 張');
    });

    await apiStep(
      'PUT /api/sitter/plans/plan-1 body：新名稱價格、defaultTasks 已刪減並新增',
      async () => {
        expect(putBodies).toHaveLength(1);
        expect(putBodies[0]).toMatchObject({
          name: '基礎照護 (新版)',
          price: 600,
          defaultTasks: ['基本餵食', '拍照 5 張']
        });
        return putBodies[0];
      }
    );
  });

  test('樂觀鎖衝突 409 錯誤處理提示', async ({ page }) => {
    const mockPlans: Plan[] = [basePlan({ defaultTasks: ['基本餵食'] })];
    let putCalls = 0;
    await mockPlanList(page, () => mockPlans);
    // 模擬後端回傳 409 Conflict
    await page.route('**/api/sitter/plans/plan-1', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      putCalls++;
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'VERSION_CONFLICT',
          message: '內容已被更新，請重新整理後再試'
        })
      });
    });

    await step('編輯方案名稱', page, async () => {
      await openPlansFromDemo(page);
      await page.getByTestId('sitter-plan-btn-edit-plan-1').click();
      await page.getByTestId('sitter-plan-input-name').fill('基礎照護 (衝突測試)');
      await expect(page.getByTestId('sitter-plan-input-name')).toHaveValue('基礎照護 (衝突測試)');
    });

    await step('儲存遇 409：表單顯示「內容已被更新」紅字、表單保持開啟', page, async () => {
      await page.getByTestId('sitter-plan-btn-save').click();
      await expect.poll(() => putCalls).toBe(1);
      await expect(page.getByTestId('sitter-plan-form-error')).toBeVisible();
      await expect(page.getByTestId('sitter-plan-form-error')).toContainText(
        '內容已被更新，請重新整理後再試'
      );
      // 表單在 modal 內自己捲動，整頁截圖只拍得到 modal 可視區：把紅字捲進畫面再截
      await page.getByTestId('sitter-plan-form-error').scrollIntoViewIfNeeded();
      await expect(page.getByTestId('sitter-plan-input-name')).toHaveValue('基礎照護 (衝突測試)');
    });
  });

  test('SaaS 日期區間 403 錯誤處理提示', async ({ page }) => {
    let postCalls = 0;
    await mockPlanList(
      page,
      () => [],
      async (route) => {
        postCalls++;
        // 模擬後端回傳 403 SaaS Limit
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({
            error: 'AUTH_PLAN_LIMIT',
            message: '僅限專業版以上方案可設定開放預約區間'
          })
        });
      }
    );

    await step('新增方案並勾選限制日期區間', page, async () => {
      await openPlansFromDemo(page);
      await page.getByTestId('sitter-plan-btn-add').click();
      await page.getByTestId('sitter-plan-input-name').fill('進階暑期照顧');
      await page.getByTestId('sitter-plan-input-price').fill('800');
      await expect(page.getByTestId('sitter-plan-input-capacity')).toHaveCount(0);
      await page.getByTestId('sitter-plan-switch-date-range').click();
      await page.getByTestId('sitter-plan-input-start-date').fill('2026-06-01');
      await page.getByTestId('sitter-plan-input-end-date').fill('2026-08-31');
      await expect(page.getByTestId('sitter-plan-input-end-date')).toHaveValue('2026-08-31');
    });

    await step('儲存遇 403：表單顯示「僅限專業版以上」紅字', page, async () => {
      await page.getByTestId('sitter-plan-btn-save').click();
      await expect.poll(() => postCalls).toBe(1);
      await expect(page.getByTestId('sitter-plan-form-error')).toBeVisible();
      await expect(page.getByTestId('sitter-plan-form-error')).toContainText(
        '僅限專業版以上方案可設定開放預約區間'
      );
      // 表單在 modal 內自己捲動，整頁截圖只拍得到 modal 可視區：把紅字捲進畫面再截
      await page.getByTestId('sitter-plan-form-error').scrollIntoViewIfNeeded();
    });
  });

  test('方案排序調整與下架功能', async ({ page }) => {
    let mockPlans: Plan[] = [
      basePlan({ id: 'plan-1', name: '方案一', price: 500, defaultTasks: [], sortOrder: 0 }),
      basePlan({ id: 'plan-2', name: '方案二', price: 800, defaultTasks: [], sortOrder: 1 })
    ];
    const sortBodies: { planIds: string[] }[] = [];
    let deactivateBodies: unknown[] = [];
    const dialogs = trackDialogs(page);

    await mockPlanList(page, () => mockPlans);
    await page.route('**/api/sitter/plans/sort', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      sortBodies.push(route.request().postDataJSON());
      // 交換本地資料庫順序以模擬重新取得資料
      mockPlans = [mockPlans[1], mockPlans[0]];
      await ok(route, null, '修改成功');
    });
    await page.route('**/api/sitter/plans/plan-1/active', async (route) => {
      if (route.request().method() !== 'PATCH') return route.fallback();
      deactivateBodies = [...deactivateBodies, route.request().postDataJSON()];
      const target = mockPlans.find((p) => p.id === 'plan-1')!;
      target.isActive = false;
      await ok(route, { ...target }, '已下架');
    });

    const cards = page.locator('[data-testid^="sitter-plan-card-"]');

    await step('排序前：方案一在前、方案二在後', page, async () => {
      await openPlansFromDemo(page);
      await expect(cards.nth(0)).toContainText('方案一');
      await expect(cards.nth(1)).toContainText('方案二');
    });

    await step('方案二上移後：方案二在前、方案一在後', page, async () => {
      await page.getByTestId('sitter-plan-btn-move-up-plan-2').click();
      await expect.poll(() => sortBodies.length).toBe(1);
      await expect(cards.nth(0)).toContainText('方案二');
      await expect(cards.nth(1)).toContainText('方案一');
    });

    await step('下架方案一：確認後卡片保留並標示已下架、改顯示上架按鈕', page, async () => {
      await page.getByTestId('sitter-plan-btn-delete-plan-1').click();
      await expectDialog(dialogs, /確定要下架此服務方案嗎？/);
      await expect.poll(() => deactivateBodies.length).toBe(1);
      // 下架後卡片應仍保留在保母自己的清單中 (只是標示已下架)，而非直接消失
      await expect(page.getByTestId('sitter-plan-card-plan-1')).toBeVisible();
      await expect(page.getByTestId('sitter-plan-card-plan-1')).toContainText('已下架');
      // 且應改為顯示「上架」按鈕，可重新上架
      await expect(page.getByTestId('sitter-plan-btn-activate-plan-1')).toBeVisible();
    });

    await apiStep('排序送出 [plan-2, plan-1]、下架只呼叫一次且沒有失敗對話框', async () => {
      // 確保發送的 id 順序正確 (上移後，應該是 [plan-2, plan-1])
      expect(sortBodies).toEqual([{ planIds: ['plan-2', 'plan-1'] }]);
      expect(deactivateBodies).toHaveLength(1);
      expect(dialogs.map((d) => d.message)).not.toContainEqual(expect.stringMatching(/失敗/));
      return { sortBodies, deactivateBodies, dialogs };
    });
  });
});
