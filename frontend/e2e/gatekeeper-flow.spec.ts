import { test, expect, type Page } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession } from './support/mockSession';

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

  // N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501 並在這裡變紅。
  // fix-batch10 H1：保母端已沒有自助升級入口，前端若再打 /api/sitter/gatekeeper/subscription/mock
  // （正式站已不存在，404）也會落到 catch-all 而紅
  test.afterEach(({ page }) => assertNoUnmockedApi(page));

  /**
   * 方案等級由管理員在後台開通（POST /api/admin/subscriptions/{id}），保母端只能讀。
   * 這裡改 mock 的 GET /subscription 回應，再從 demo 首頁進門禁頁，等同「管理員開通後保母重新進頁」
   */
  async function openGatekeeperAs(page: Page, tier: 'FREE' | 'PRO' | 'ULTIMATE') {
    mockSubscription.planTier = tier;
    await page.click('[data-testid="btn-go-gatekeeper"]');
    await expect(page.locator('h2')).toContainText(
      tier === 'FREE' ? '解鎖預約門禁系統 (Gatekeeper)' : '預約門禁管理系統'
    );
  }

  test.beforeEach(async ({ page }) => {
    // 共用 header API mock + 漏 mock 偵測；本檔的 login / refresh / 頁面 API route 後註冊，優先於共用 mock
    await mockDemoSession(page, 'sitter');

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
    await page.route(
      (url) => url.pathname.includes('/api/auth/login'),
      async (route) => {
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
      }
    );

    // Mock Token Refresh
    await page.route(
      (url) => url.pathname.includes('/api/auth/refresh'),
      async (route) => {
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
      }
    );

    // Mock 角色切換 API
    await page.route(
      (url) => url.pathname.includes('/api/auth/switch-role'),
      async (route) => {
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
      }
    );

    // Mock Subscription status
    await page.route(
      (url) => url.pathname === '/api/sitter/gatekeeper/subscription',
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(mockSubscription)
        });
      }
    );

    // Mock Rules list
    await page.route(
      (url) => url.pathname === '/api/sitter/gatekeeper',
      async (route, request) => {
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
            (r) =>
              r.targetEmail === 'ow***@test.com' &&
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
      }
    );

    await page.route(
      (url) =>
        url.pathname.startsWith('/api/sitter/gatekeeper/') &&
        !url.pathname.includes('/subscription'),
      async (route, request) => {
        if (request.method() === 'DELETE') {
          const urlStr = request.url();
          const ruleId = urlStr.substring(urlStr.lastIndexOf('/') + 1);
          mockRules = mockRules.filter((r) => r.id !== ruleId);
          await route.fulfill({ status: 204 });
        }
      }
    );

    // Mock plans list
    await page.route(
      (url) => url.pathname === '/api/sitter/plans',
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: mockPlans })
        });
      }
    );

    await page.goto('/');
  });

  test('TS-001-01 角色切換 lazy profile 建立', async ({ page }) => {
    await step('切換為飼主', page, async () => {
      await page.click('[data-testid="btn-role-toggle"]');
      await expect(page.locator('p').filter({ hasText: '當前角色:' })).toContainText('愛貓飼主');
    });

    await step('切回保母', page, async () => {
      await page.click('button:has-text("切換為保母")');
      await expect(page.locator('p').filter({ hasText: '當前角色:' })).toContainText('貓咪保母');
    });
  });

  test('TS-001-02 SaaS 方案攔截，開通只能由管理員（保母端無自助升級）', async ({ page }) => {
    await step('FREE 進門禁頁：鎖定畫面＋請聯繫平台管理員開通，無任何升級按鈕', page, async () => {
      await openGatekeeperAs(page, 'FREE');
      await expect(page.getByTestId('gatekeeper-contact-admin')).toHaveText(
        '目前方案：FREE。如需開通專業版 / 頂級版，請聯繫平台管理員開通。'
      );
      await expect(page.getByRole('button', { name: /模擬|升級/ })).toHaveCount(0);
    });

    await step(
      '管理員開通 PRO 後重新進頁：黑名單可用、白名單與免填問卷 disabled',
      page,
      async () => {
        mockSubscription.planTier = 'PRO';
        await page.reload();
        await expect(page.locator('h2')).toContainText('預約門禁管理系統');
        await expect(page.locator('span:has-text("方案等級: PRO")')).toBeVisible();
        // 啟用畫面也不能有自助升級 / 降級按鈕
        await expect(page.getByRole('button', { name: /模擬|升級|降級/ })).toHaveCount(0);
        const selectRule = page.getByTestId('select-rule-type');
        await expect(selectRule.locator('option[value="WHITE"]')).toBeDisabled();
        await expect(selectRule.locator('option[value="NO_QUESTIONNAIRE"]')).toBeDisabled();
      }
    );

    await step('管理員開通 ULTIMATE 後重新進頁：白名單與免填問卷可選', page, async () => {
      mockSubscription.planTier = 'ULTIMATE';
      await page.reload();
      await expect(page.locator('span:has-text("方案等級: ULTIMATE")')).toBeVisible();
      const selectRule = page.getByTestId('select-rule-type');
      await expect(selectRule.locator('option[value="WHITE"]')).not.toBeDisabled();
      await expect(selectRule.locator('option[value="NO_QUESTIONNAIRE"]')).not.toBeDisabled();
    });
  });

  test('TS-001-03 門禁白名單與黑名單互斥防禦', async ({ page }) => {
    await step('ULTIMATE 進門禁頁，既有 GLOBAL 黑名單可見', page, async () => {
      await openGatekeeperAs(page, 'ULTIMATE');
      await expect(page.getByText('ow***@test.com')).toBeVisible();
    });

    await step('同對象同範圍新增白名單：顯示互斥錯誤', page, async () => {
      await page.fill('[data-testid="input-target-email"]', 'owner@test.com');
      await page.click('[data-testid="btn-scope-global"]');
      const selectRule = page.getByTestId('select-rule-type');
      await selectRule.selectOption('WHITE');
      await page.click('button:has-text("新增門禁規則")');
      await expect(
        page.getByText('同一對象在同範圍內不能同時並存於黑名單與白名單中')
      ).toBeVisible();
    });
  });

  test('TS-001-04 全域/方案級黑名單與白名單規則操作', async ({ page }) => {
    await step('ULTIMATE 進門禁頁', page, async () => {
      await openGatekeeperAs(page, 'ULTIMATE');
      await expect(page.getByTestId('gatekeeper-rule-row')).toHaveCount(1);
    });

    await step('新增方案級黑名單：清單變 2 筆', page, async () => {
      await page.fill('[data-testid="input-target-email"]', 'owner@test.com');
      await page.click('[data-testid="btn-scope-plan"]');
      await page.selectOption('[data-testid="select-plan"]', { label: '精緻貓咪托育' });
      const selectRule = page.getByTestId('select-rule-type');
      await selectRule.selectOption('BLACK');
      await page.click('button:has-text("新增門禁規則")');
      await expect(page.getByTestId('gatekeeper-rule-row')).toHaveCount(2);
    });

    await step('刪除第 2 筆：清單回 1 筆', page, async () => {
      await page.getByTestId('gatekeeper-rule-row').nth(1).locator('button').click();
      await expect(page.getByTestId('gatekeeper-rule-row')).toHaveCount(1);
    });
  });

  test('TS-001-05 方案降級規則失效防禦', async ({ page }) => {
    await step('PRO 進門禁頁：設定清單可見', page, async () => {
      await openGatekeeperAs(page, 'PRO');
      await expect(page.locator('h3:has-text("目前門禁設定清單")')).toBeVisible();
    });

    await step('管理員降回 FREE（或到期回落）後重新進頁：回到鎖定畫面', page, async () => {
      mockSubscription.planTier = 'FREE';
      await page.reload();
      await expect(page.locator('h2')).toContainText('解鎖預約門禁系統 (Gatekeeper)');
      await expect(page.locator('h3:has-text("目前門禁設定清單")')).toHaveCount(0);
    });
  });
});
