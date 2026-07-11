import { test, expect } from '@playwright/test';

test.describe('Gatekeeper 預約門禁與角色切換流程 E2E', () => {

  let mockSubscription = {
    planTier: 'FREE',
    expiredAt: null
  };

  let mockRules = [
    {
      id: 'g1023000-0000-0000-0000-000000000001',
      sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
      ruleType: 'BLACK',
      scopeType: 'GLOBAL',
      planId: null,
      targetUserId: '1031efbc-583a-4062-9a35-15706a3384c6',
      targetEmail: 'ow***@test.com'
    }
  ];

  const mockPlans = [
    {
      id: 'p1023000-0000-0000-0000-000000000001',
      name: '精緻貓咪托育'
    }
  ];

  test.beforeEach(async ({ page }) => {
    // 重設為初始 Free 狀態
    mockSubscription = {
      planTier: 'FREE',
      expiredAt: null
    };

    mockRules = [
      {
        id: 'g1023000-0000-0000-0000-000000000001',
        sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
        ruleType: 'BLACK',
        scopeType: 'GLOBAL',
        planId: null,
        targetUserId: '1031efbc-583a-4062-9a35-15706a3384c6',
        targetEmail: 'ow***@test.com'
      }
    ];

    // Mock Auto Login
    await page.route(url => url.pathname.includes('/api/auth/login'), async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'fake-initial-token',
          refreshToken: 'fake-initial-refresh',
          userId: '3d498178-14c0-4376-b81e-7fb02e615dda',
          email: 'sitter@test.com',
          fullName: '貓咪保母',
          role: 'SITTER'
        })
      });
    });

    // Mock Token Refresh
    await page.route(url => url.pathname.includes('/api/auth/refresh'), async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'fake-refreshed-token',
          refreshToken: 'fake-refreshed-refresh',
          userId: '3d498178-14c0-4376-b81e-7fb02e615dda',
          email: 'sitter@test.com',
          fullName: '貓咪保母',
          role: 'SITTER'
        })
      });
    });

    // Mock 角色切換 API
    await page.route(url => url.pathname.includes('/api/auth/switch-role'), async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'fake-switched-token',
          refreshToken: 'fake-switched-refresh',
          userId: '3d498178-14c0-4376-b81e-7fb02e615dda',
          email: 'sitter@test.com',
          fullName: '貓咪保母',
          role: 'SITTER'
        })
      });
    });

    // Mock Subscription status
    await page.route(url => url.pathname === '/api/sitter/gatekeeper/subscription', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockSubscription)
      });
    });

    // Mock subscription simulator
    await page.route(url => url.pathname === '/api/sitter/gatekeeper/subscription/mock', async (route, request) => {
      const payload = JSON.parse(request.postData() || '{}');
      mockSubscription.planTier = payload.planTier || 'FREE';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockSubscription)
      });
    });

    // Mock Rules list
    await page.route(url => url.pathname === '/api/sitter/gatekeeper', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(mockRules)
        });
      } else if (request.method() === 'POST') {
        const payload = JSON.parse(request.postData() || '{}');
        
        // 模擬互斥：若 BLACK 和 WHITE 作用對象及 scope 都一樣，回傳 400
        const isConflict = mockRules.some(
          r => r.targetEmail === 'ow***@test.com' && 
          r.scopeType === payload.scopeType && 
          r.ruleType !== payload.ruleType
        );
        
        if (isConflict) {
          await route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({ message: '同一對象在同範圍內不能同時並存於黑名單與白名單中' })
          });
          return;
        }

        const newRule = {
          id: 'g1023000-0000-0000-0000-000000000002',
          sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
          ruleType: payload.ruleType,
          scopeType: payload.scopeType,
          planId: payload.planId,
          targetUserId: '1031efbc-583a-4062-9a35-15706a3384c6',
          targetEmail: 'ow***@test.com'
        };
        mockRules.push(newRule);
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(newRule)
        });
      }
    });

    await page.route(url => url.pathname.startsWith('/api/sitter/gatekeeper/') && !url.pathname.includes('/subscription'), async (route, request) => {
      if (request.method() === 'DELETE') {
        const urlStr = request.url();
        const ruleId = urlStr.substring(urlStr.lastIndexOf('/') + 1);
        mockRules = mockRules.filter(r => r.id !== ruleId);
        await route.fulfill({ status: 204 });
      }
    });

    // Mock plans list
    await page.route(url => url.pathname === '/api/sitter/plans', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'OK', data: mockPlans })
      });
    });

    await page.goto('/');
  });

  test('TS-001-01 角色切換 lazy profile 建立', async ({ page }) => {
    // 從預設 sitter 切換為 client
    await page.click('[data-testid="btn-role-toggle"]');
    
    // 檢查角色狀態更新
    await expect(page.locator('p').filter({ hasText: '當前角色:' })).toContainText('愛貓飼主');
    
    // 再切回 sitter
    await page.click('button:has-text("切換為保母")');
    await expect(page.locator('p').filter({ hasText: '當前角色:' })).toContainText('貓咪保母');
  });

  test('TS-001-02 SaaS 方案攔截與解鎖', async ({ page }) => {
    // 進入保母端門禁設定頁面
    await page.click('[data-testid="btn-go-gatekeeper"]');

    // Free 方案，預期顯示 SaaS 方案鎖定
    await expect(page.locator('h2')).toContainText('解鎖預約門禁系統 (Gatekeeper)');

    // 模擬升級為專業版 (PRO)
    await page.click('button:has-text("模擬升級專業版 (PRO)")');

    // 升級成功，Lock Screen 消失，呈現門禁規則設定區
    await expect(page.locator('h2')).toContainText('預約門禁管理系統');
    await expect(page.locator('span:has-text("方案等級: PRO")')).toBeVisible();

    // 專業版：檢查白名單與問卷豁免選項是否已 disabled
    const selectRule = page.getByTestId('select-rule-type'); // 規則類型 dropdown
    await expect(selectRule.locator('option[value="WHITE"]')).toBeDisabled();
    await expect(selectRule.locator('option[value="NO_QUESTIONNAIRE"]')).toBeDisabled();

    // 模擬升級為頂級版 (ULTIMATE)
    await page.click('button:has-text("模擬升級頂級版 (ULTIMATE)")');
    await expect(page.locator('span:has-text("方案等級: ULTIMATE")')).toBeVisible();

    // 頂級版：白名單與問卷豁免選項應為啟用狀態
    await expect(selectRule.locator('option[value="WHITE"]')).not.toBeDisabled();
    await expect(selectRule.locator('option[value="NO_QUESTIONNAIRE"]')).not.toBeDisabled();
  });

  test('TS-001-03 門禁白名單與黑名單互斥防禦', async ({ page }) => {
    // 進入門禁設定並直接升級成頂級版
    await page.click('[data-testid="btn-go-gatekeeper"]');
    await page.click('button:has-text("模擬升級頂級版 (ULTIMATE)")');

    // 清單中目前已有一個 GLOBAL - BLACK 規則 (對象是 ow***@test.com)
    await expect(page.getByText('ow***@test.com')).toBeVisible();

    // 嘗試在同範圍 (GLOBAL) 對同對象新增一個 WHITE 規則
    await page.fill('[data-testid="input-target-email"]', 'owner@test.com');
    await page.click('[data-testid="btn-scope-global"]');
    const selectRule = page.getByTestId('select-rule-type');
    await selectRule.selectOption('WHITE');
    await page.click('button:has-text("新增門禁規則")');

    // 預期出現後端傳回的互斥防禦錯誤提示
    await expect(page.getByText('同一對象在同範圍內不能同時並存於黑名單與白名單中')).toBeVisible();
  });

  test('TS-001-04 全域/方案級黑名單與白名單規則操作', async ({ page }) => {
    // 進入門禁設定並解鎖
    await page.click('[data-testid="btn-go-gatekeeper"]');
    await page.click('button:has-text("模擬升級頂級版 (ULTIMATE)")');

    // 新增一筆方案級的黑名單規則
    await page.fill('[data-testid="input-target-email"]', 'owner@test.com');
    await page.click('[data-testid="btn-scope-plan"]');
    await page.selectOption('[data-testid="select-plan"]', { label: '精緻貓咪托育' });
    const selectRule = page.getByTestId('select-rule-type');
    await selectRule.selectOption('BLACK');
    await page.click('button:has-text("新增門禁規則")');

    // 驗證是否出現在清單中 (共 2 筆)
    await expect(page.getByTestId('gatekeeper-rule-row')).toHaveCount(2);

    // 測試刪除規則
    const deleteButton = page.getByTestId('gatekeeper-rule-row').nth(1).locator('button');
    await deleteButton.click();

    // 刪除成功，清單回復為 1 筆
    await expect(page.getByTestId('gatekeeper-rule-row')).toHaveCount(1);
  });

  test('TS-001-05 方案降級規則失效防禦', async ({ page }) => {
    // 進入門禁設定並解鎖
    await page.click('[data-testid="btn-go-gatekeeper"]');
    await page.click('button:has-text("模擬升級專業版 (PRO)")');

    // 正常設定清單可見
    await expect(page.locator('h3:has-text("目前門禁設定清單")')).toBeVisible();

    // 降級為 FREE
    await page.click('button:has-text("模擬降級 Free")');

    // 預期頁面即刻重回鎖定畫面，限制使用
    await expect(page.locator('h2')).toContainText('解鎖預約門禁系統 (Gatekeeper)');
  });

});
