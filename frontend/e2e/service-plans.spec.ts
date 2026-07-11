import { test, expect } from '@playwright/test';

test.describe('SD-003 保母服務方案設定 E2E 測試', () => {

  test.beforeEach(async ({ page }) => {
    // 預設將 Sitter 角色 Token 寫入，方便通過 API 驗證
    await page.addInitScript(() => {
      window.localStorage.setItem('accessToken', 'mock-sitter-token');
      window.localStorage.setItem('role', 'sitter');
    });
  });

  test('保母成功查詢方案列表與新增常態方案', async ({ page }) => {
    // Mock 方案列表查詢 API (GET /api/sitter/plans)
    let mockPlans = [
      {
        id: 'plan-1',
        sitterId: 'sitter-123',
        name: '基礎照護',
        price: 500,
        dailyCapacity: 3,
        defaultTasks: ['基本餵食', '清理砂盆'],
        applicablePetTypes: ['CAT'],
        description: '標準貓咪照顧',
        startDate: null,
        endDate: null,
        isRestricted: false,
        sortOrder: 0,
        version: 0
      }
    ];

    await page.route('**/api/sitter/plans', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: mockPlans })
        });
      } else if (request.method() === 'POST') {
        const body = request.postDataJSON();
        const newPlan = { ...body, id: 'plan-2', version: 0, sortOrder: mockPlans.length };
        mockPlans.push(newPlan);
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: '新增成功', data: newPlan })
        });
      }
    });

    await page.goto('/');
    // 點擊「進入方案設定 (保母端)」按鈕
    await page.click('[data-testid="btn-go-sitter-plans"]');

    // 檢查既有方案卡片
    await expect(page.getByTestId('sitter-plan-card-plan-1')).toBeVisible();
    await expect(page.locator('text=基礎照護')).toBeVisible();
    await expect(page.locator('text=$ 500')).toBeVisible();

    // 點擊「新增方案」
    await page.click('[data-testid="sitter-plan-btn-add"]');

    // 填寫表單
    await page.fill('[data-testid="sitter-plan-input-name"]', '暑假豪華陪玩');
    await page.fill('[data-testid="sitter-plan-input-price"]', '1200');
    await page.fill('[data-testid="sitter-plan-input-capacity"]', '2');
    
    // 適用寵物：點擊狗狗 (貓咪是預選)
    await page.click('[data-testid="sitter-plan-checkbox-pet-DOG"]');
    
    await page.fill('[data-testid="sitter-plan-input-description"]', '超長陪伴，包含梳毛與陪玩');
    
    // 填寫第一個 SOP 任務
    await page.fill('[data-testid="sitter-plan-input-task-0"]', '梳毛 15 分鐘');

    // 儲存
    await page.click('[data-testid="sitter-plan-btn-save"]');

    // 檢查新方案是否成功出現在清單中
    await expect(page.getByTestId('sitter-plan-card-plan-2')).toBeVisible();
    await expect(page.locator('text=暑假豪華陪玩')).toBeVisible();
    await expect(page.locator('text=$ 1200')).toBeVisible();
  });

  test('保母編輯方案與預設任務動態刪減', async ({ page }) => {
    let mockPlans = [
      {
        id: 'plan-1',
        sitterId: 'sitter-123',
        name: '基礎照護',
        price: 500,
        dailyCapacity: 3,
        defaultTasks: ['基本餵食', '清理砂盆'],
        applicablePetTypes: ['CAT'],
        description: '標準貓咪照顧',
        startDate: null,
        endDate: null,
        isRestricted: false,
        sortOrder: 0,
        version: 0
      }
    ];

    await page.route('**/api/sitter/plans', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: mockPlans })
        });
      }
    });

    await page.route('**/api/sitter/plans/plan-1', async (route, request) => {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON();
        mockPlans[0] = { ...mockPlans[0], ...body, version: 1 };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: '修改成功', data: mockPlans[0] })
        });
      }
    });

    await page.goto('/');
    await page.click('[data-testid="btn-go-sitter-plans"]');

    // 點擊編輯
    await page.click('[data-testid="sitter-plan-btn-edit-plan-1"]');

    // 更改名稱與價格
    await page.fill('[data-testid="sitter-plan-input-name"]', '基礎照護 (新版)');
    await page.fill('[data-testid="sitter-plan-input-price"]', '600');

    // 測試動態 SOP：移除第二個任務 ('清理砂盆')
    await page.click('[data-testid="sitter-plan-btn-delete-task-1"]');

    // 新增一項任務
    await page.click('[data-testid="sitter-plan-btn-add-task"]');
    await page.fill('[data-testid="sitter-plan-input-task-1"]', '拍照 5 張');

    // 儲存
    await page.click('[data-testid="sitter-plan-btn-save"]');

    // 確認 UI 更新
    await expect(page.locator('text=基礎照護 (新版)')).toBeVisible();
    await expect(page.locator('text=$ 600')).toBeVisible();
    await expect(page.locator('text=拍照 5 張')).toBeVisible();
  });

  test('樂觀鎖衝突 409 錯誤處理提示', async ({ page }) => {
    const mockPlans = [
      {
        id: 'plan-1',
        sitterId: 'sitter-123',
        name: '基礎照護',
        price: 500,
        dailyCapacity: 3,
        defaultTasks: ['基本餵食'],
        applicablePetTypes: ['CAT'],
        description: '標準貓咪照顧',
        startDate: null,
        endDate: null,
        isRestricted: false,
        sortOrder: 0,
        version: 0
      }
    ];

    await page.route('**/api/sitter/plans', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: mockPlans })
        });
      }
    });

    // 模擬後端回傳 409 Conflict
    await page.route('**/api/sitter/plans/plan-1', async (route, request) => {
      if (request.method() === 'PUT') {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'VERSION_CONFLICT', message: '內容已被更新，請重新整理後再試' })
        });
      }
    });

    await page.goto('/');
    await page.click('[data-testid="btn-go-sitter-plans"]');

    await page.click('[data-testid="sitter-plan-btn-edit-plan-1"]');
    await page.fill('[data-testid="sitter-plan-input-name"]', '基礎照護 (衝突測試)');
    await page.click('[data-testid="sitter-plan-btn-save"]');

    // 應顯示錯誤紅字提示
    await expect(page.getByTestId('sitter-plan-form-error')).toBeVisible();
    await expect(page.getByTestId('sitter-plan-form-error')).toContainText('內容已被更新，請重新整理後再試');
  });

  test('SaaS 日期區間 403 錯誤處理提示', async ({ page }) => {
    const mockPlans = [];

    await page.route('**/api/sitter/plans', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: mockPlans })
        });
      } else if (request.method() === 'POST') {
        // 模擬後端回傳 403 SaaS Limit
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'AUTH_PLAN_LIMIT', message: '僅限專業版以上方案可設定開放預約區間' })
        });
      }
    });

    await page.goto('/');
    await page.click('[data-testid="btn-go-sitter-plans"]');

    await page.click('[data-testid="sitter-plan-btn-add"]');
    await page.fill('[data-testid="sitter-plan-input-name"]', '進階暑期照顧');
    await page.fill('[data-testid="sitter-plan-input-price"]', '800');
    await page.fill('[data-testid="sitter-plan-input-capacity"]', '3');

    // 勾選限制日期區間
    await page.click('[data-testid="sitter-plan-switch-date-range"]');
    await page.fill('[data-testid="sitter-plan-input-start-date"]', '2026-06-01');
    await page.fill('[data-testid="sitter-plan-input-end-date"]', '2026-08-31');

    await page.click('[data-testid="sitter-plan-btn-save"]');

    // 應顯示限制提示紅字
    await expect(page.getByTestId('sitter-plan-form-error')).toBeVisible();
    await expect(page.getByTestId('sitter-plan-form-error')).toContainText('僅限專業版以上方案可設定開放預約區間');
  });

  test('方案排序調整與下架功能', async ({ page }) => {
    let mockPlans = [
      {
        id: 'plan-1',
        sitterId: 'sitter-123',
        name: '方案一',
        price: 500,
        dailyCapacity: 3,
        defaultTasks: [],
        applicablePetTypes: ['CAT'],
        isRestricted: false,
        sortOrder: 0,
        version: 0
      },
      {
        id: 'plan-2',
        sitterId: 'sitter-123',
        name: '方案二',
        price: 800,
        dailyCapacity: 3,
        defaultTasks: [],
        applicablePetTypes: ['CAT'],
        isRestricted: false,
        sortOrder: 1,
        version: 0
      }
    ];

    let sortCalled = false;
    let deactivateCalled = false;

    await page.route('**/api/sitter/plans', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: mockPlans })
        });
      }
    });

    await page.route('**/api/sitter/plans/sort', async (route, request) => {
      if (request.method() === 'POST') {
        const body = request.postDataJSON();
        // 確保發送的 id 順序正確 (上移後，應該是 [plan-2, plan-1])
        expect(body.planIds).toEqual(['plan-2', 'plan-1']);
        sortCalled = true;
        // 交換本地資料庫順序以模擬重新取得資料
        mockPlans = [mockPlans[1], mockPlans[0]];
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: '修改成功', data: null })
        });
      }
    });

    await page.route('**/api/sitter/plans/plan-1/active', async (route, request) => {
      if (request.method() === 'PATCH') {
        deactivateCalled = true;
        const targetPlan = mockPlans.find(p => p.id === 'plan-1')!;
        (targetPlan as any).isActive = false;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: '已下架', data: { ...targetPlan } })
        });
      }
    });

    await page.goto('/');
    await page.click('[data-testid="btn-go-sitter-plans"]');

    // 檢查卡片預設順序
    const cardLocators = page.locator('[data-testid^="sitter-plan-card-"]');
    await expect(cardLocators.nth(0)).toContainText('方案一');
    await expect(cardLocators.nth(1)).toContainText('方案二');

    // 點擊「方案二」的上移按鈕 (index = 1)
    await page.click('[data-testid="sitter-plan-btn-move-up-plan-2"]');
    expect(sortCalled).toBe(true);

    // 檢查 UI 重新載入後的順序
    await expect(cardLocators.nth(0)).toContainText('方案二');
    await expect(cardLocators.nth(1)).toContainText('方案一');

    // 點擊下架「方案一」
    page.once('dialog', async (dialog) => {
      expect(dialog.message()).toContain('確定要下架此服務方案嗎？');
      await dialog.accept();
    });
    await page.click('[data-testid="sitter-plan-btn-delete-plan-1"]');
    expect(deactivateCalled).toBe(true);

    // 下架後卡片應仍保留在保母自己的清單中 (只是標示已下架)，而非直接消失
    await expect(page.getByTestId('sitter-plan-card-plan-1')).toBeVisible();
    await expect(page.getByTestId('sitter-plan-card-plan-1')).toContainText('已下架');

    // 且應改為顯示「上架」按鈕，可重新上架
    await expect(page.getByTestId('sitter-plan-btn-activate-plan-1')).toBeVisible();
  });
});
