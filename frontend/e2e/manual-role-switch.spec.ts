import { test, expect, type Page } from '@playwright/test';
import { step, trackDialogs, expectDialog } from './support/evidence';

/**
 * 迴歸 (Backlog 第 3 批 D3)：正式登入 (authMode=manual) 的使用者在 /demo 點「切換角色」，
 * 必須對自己的帳號呼叫 POST /api/auth/switch-role，不可改登種子帳號。
 * 過去 setRole() 不看 authMode，一律 POST /api/auth/login 種子帳號，真實 session 直接被換掉。
 */
const fakeJwt = (role: string) => {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'real-user@example.com', role, userId: 'u-1' })}.sig`;
};

async function setupManualSession(page: Page, role: string, appRole: string) {
  const token = fakeJwt(role);
  await page.addInitScript(
    ([t, r]) => {
      if (!sessionStorage.getItem('__seeded')) {
        sessionStorage.setItem('__seeded', '1');
        localStorage.setItem('accessToken', t);
        localStorage.setItem('refreshToken', 'real-refresh');
        localStorage.setItem('authMode', 'manual');
        localStorage.setItem('userRole', r);
      }
    },
    [token, appRole]
  );

  const seedLogins: string[] = [];
  page.on('request', (req) => {
    if (req.method() === 'POST' && new URL(req.url()).pathname === '/api/auth/login') {
      seedLogins.push(req.postData() ?? '');
    }
  });

  const switchCalls: { body: unknown; auth: string | undefined }[] = [];
  await page.route('**/api/auth/switch-role', async (route) => {
    const body = route.request().postDataJSON() as { targetRole: string };
    switchCalls.push({ body, auth: route.request().headers()['authorization'] });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: fakeJwt(body.targetRole),
        refreshToken: `refresh-${body.targetRole}`,
        role: body.targetRole
      })
    });
  });
  await page.route('**/api/notifications/unread-count**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"data":0}' })
  );
  await page.route(/\/api\/notifications(\?.*)?$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }
      })
    })
  );
  await page.route('**/api/sitter/kyc/status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { kycStatus: 'VERIFIED', rejectReason: '', submittedAt: '' } })
    })
  );
  return { token, seedLogins, switchCalls };
}

const storage = (page: Page) =>
  page.evaluate(() => ({
    accessToken: localStorage.getItem('accessToken'),
    authMode: localStorage.getItem('authMode')
  }));

test.describe('正式登入使用者於 /demo 切換角色 (D3 迴歸)', () => {
  test('飼主帳號切換為保母：走 switch-role、不登入種子帳號；切管理員被拒且 session 不變', async ({
    page
  }) => {
    const dialogs = trackDialogs(page);
    const { token, seedLogins, switchCalls } = await setupManualSession(page, 'OWNER', 'client');

    await step('以正式登入的飼主身分開 /demo', page, async () => {
      await page.goto('/demo');
      await expect(page.getByText('當前角色:')).toContainText('愛貓飼主');
      expect(seedLogins).toHaveLength(0);
    });

    await step('點「切換為保母」：對目前帳號呼叫 switch-role', page, async () => {
      await page.getByRole('button', { name: '切換為保母' }).click();
      await expect(page.getByText('當前角色:')).toContainText('貓咪保母');
      expect(switchCalls).toHaveLength(1);
      expect(switchCalls[0].body).toEqual({ targetRole: 'SITTER' });
      expect(switchCalls[0].auth).toBe(`Bearer ${token}`);
      expect(seedLogins).toHaveLength(0);
      expect(await storage(page)).toEqual({ accessToken: fakeJwt('SITTER'), authMode: 'manual' });
    });

    await step('點「切換為管理員」：非管理員帳號被拒，角色與 token 不變', page, async () => {
      await page.getByRole('button', { name: '切換為管理員' }).click();
      await expectDialog(dialogs, /此帳號沒有管理員權限/);
      await expect(page.getByText('當前角色:')).toContainText('貓咪保母');
      expect(switchCalls).toHaveLength(1);
      expect(seedLogins).toHaveLength(0);
      expect(await storage(page)).toEqual({ accessToken: fakeJwt('SITTER'), authMode: 'manual' });
    });
  });

  test('管理員帳號點「切換為保母」：被拒，不呼叫 switch-role、不登入種子帳號', async ({ page }) => {
    const dialogs = trackDialogs(page);
    const { token, seedLogins, switchCalls } = await setupManualSession(page, 'ADMIN', 'admin');

    await step('以正式登入的管理員身分開 /demo', page, async () => {
      await page.goto('/demo');
      await expect(page.getByText('當前角色:')).toContainText('系統管理員');
    });

    await step('點「切換為保母」被拒', page, async () => {
      await page.getByRole('button', { name: '切換為保母' }).click();
      await expectDialog(dialogs, /管理員帳號沒有保母\/飼主身分/);
      await expect(page.getByText('當前角色:')).toContainText('系統管理員');
      expect(switchCalls).toHaveLength(0);
      expect(seedLogins).toHaveLength(0);
      expect(await storage(page)).toEqual({ accessToken: token, authMode: 'manual' });
    });
  });
});
