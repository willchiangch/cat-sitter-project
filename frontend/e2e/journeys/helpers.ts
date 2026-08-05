import fs from 'fs';
import path from 'path';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import type { APIRequestContext, Page, TestInfo } from '@playwright/test';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * 共用工具，給 frontend/e2e/journeys/ 底下的跨模組真後端 journey 測試使用。
 *
 * 這批測試打的是真後端 + 真資料庫（正式站，見 CI 的 BASE_URL），不像其他 18 支既有
 * e2e 測試用 page.route() mock API。帳號一律用 @e2e-journey.test 網域即時建立
 * （見 E2eJourneyAccountProvisioningService / E2eJourneyDataCleanupService），
 * 不共用 sitter@test.com / owner@test.com 這組持久種子帳號。
 */

const INTERNAL_SECRET = process.env.INTERNAL_CRON_SECRET || 'local-secret-123';

// 統一密碼，反正帳號是每次即時建立、用完即丟
const TEST_PASSWORD = 'JourneyTest-2026!';

export function uniqueTestEmail(scenario: string, role: string): string {
  const rand = Math.random().toString(36).slice(2, 8);
  return `journey-${scenario}-${role}-${Date.now()}-${rand}@e2e-journey.test`;
}

export interface TestAccount {
  email: string;
  password: string;
  userId: string;
  accessToken: string;
  refreshToken: string;
}

/**
 * 打 /api/internal/test-data/provision-account 直接建立「等同 OTP 已驗證完成」的帳號
 * （略過寄信/驗證這一步，理由見計畫架構決策 3），接著走真實的 POST /api/auth/login 取得 token。
 */
export async function provisionAndLogin(
  request: APIRequestContext,
  scenario: string,
  role: 'OWNER' | 'SITTER' | 'ADMIN',
  fullName: string
): Promise<TestAccount> {
  const email = uniqueTestEmail(scenario, role.toLowerCase());

  const provisionRes = await request.post('/api/internal/test-data/provision-account', {
    headers: { 'X-Internal-Secret': INTERNAL_SECRET },
    data: { email, password: TEST_PASSWORD, fullName, role }
  });
  if (!provisionRes.ok()) {
    throw new Error(`provision-account failed (${provisionRes.status()}): ${await provisionRes.text()}`);
  }
  const { userId } = await provisionRes.json();

  const loginRes = await request.post('/api/auth/login', { data: { email, password: TEST_PASSWORD } });
  if (!loginRes.ok()) {
    throw new Error(`login failed after provisioning (${loginRes.status()}): ${await loginRes.text()}`);
  }
  const loginBody = await loginRes.json();

  return {
    email,
    password: TEST_PASSWORD,
    userId,
    accessToken: loginBody.accessToken,
    refreshToken: loginBody.refreshToken
  };
}

/**
 * 打 /api/auth/switch-role。第一次針對某個 targetRole 呼叫時，後端會 lazy 建立對應的
 * Profile（kycStatus=UNVERIFIED），這是全專案目前唯一會測到這條路徑的地方。
 */
export async function switchRole(
  request: APIRequestContext,
  account: TestAccount,
  targetRole: 'OWNER' | 'SITTER'
): Promise<TestAccount> {
  const res = await request.post('/api/auth/switch-role', {
    headers: apiAuthed(account),
    data: { targetRole }
  });
  if (!res.ok()) {
    throw new Error(`switch-role failed (${res.status()}): ${await res.text()}`);
  }
  const body = await res.json();
  return { ...account, accessToken: body.accessToken, refreshToken: body.refreshToken ?? account.refreshToken };
}

/**
 * 用既有共用種子管理員帳號（admin@test.com）登入，取得真實 token。
 * 全系統只有這唯一固定的管理員帳號，不像飼主/保母走 @e2e-journey.test 動態建立。
 */
export async function loginAsSeedAdmin(request: APIRequestContext): Promise<TestAccount> {
  const res = await request.post('/api/auth/login', {
    data: { email: 'admin@test.com', password: 'password' }
  });
  if (!res.ok()) {
    throw new Error(`admin login failed (${res.status()}): ${await res.text()}`);
  }
  const body = await res.json();
  return {
    email: 'admin@test.com',
    password: 'password',
    userId: body.userId,
    accessToken: body.accessToken,
    refreshToken: body.refreshToken
  };
}

export function apiAuthed(account: TestAccount): Record<string, string> {
  return { Authorization: `Bearer ${account.accessToken}` };
}

/**
 * 把 API 建立好的帳號（provisionAndLogin/switchRole 拿到的 token）寫進瀏覽器 localStorage，
 * 讓後續 page.goto() 直接進到已登入狀態，不用每個 journey 步驟都重新走一次 UI 登入表單。
 * 寫法比照 LoginPage.tsx 的 applyAuthResponseAndRedirect（authMode='manual' 避免被種子帳號自動登入蓋掉）。
 */
export async function setBrowserAuth(
  page: Page,
  account: TestAccount,
  role: 'client' | 'sitter' | 'admin'
) {
  await page.goto('/login');
  await page.evaluate(
    ([token, refresh, r]) => {
      localStorage.setItem('accessToken', token as string);
      localStorage.setItem('refreshToken', refresh as string);
      localStorage.setItem('authMode', 'manual');
      localStorage.setItem('userRole', r as string);
    },
    [account.accessToken, account.refreshToken, role]
  );
}

/** 走真實 /login 頁面登入（比照 webauthn-login.spec.ts 的作法），可選帶 redirect 參數 */
export async function loginUI(page: Page, email: string, password: string, redirectPath?: string) {
  const target = redirectPath ? `/login?redirect=${encodeURIComponent(redirectPath)}` : '/login';
  await page.goto(target);
  await page.getByTestId('login-email-input').fill(email);
  await page.getByTestId('login-password-input').fill(password);
  await page.getByTestId('login-submit-btn').click();
}

interface AwaitNotificationOptions {
  category: string;
  role?: 'OWNER' | 'SITTER' | 'ADMIN';
  predicate?: (notification: any) => boolean;
  timeoutMs?: number;
}

/**
 * 輪詢 GET /api/notifications 直到符合條件的通知出現。
 * 通知是 @Async + AFTER_COMMIT 觸發，動作 API 回應完成不代表通知已寫入 DB，不能立刻斷言。
 */
export async function awaitNotification(
  request: APIRequestContext,
  account: TestAccount,
  options: AwaitNotificationOptions
): Promise<any> {
  const timeoutMs = options.timeoutMs ?? 15000;
  const intervalMs = 1000;
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const query = options.role ? `?page=0&size=20&role=${options.role}` : '?page=0&size=20';
    const res = await request.get(`/api/notifications${query}`, { headers: apiAuthed(account) });
    if (res.ok()) {
      const body = await res.json();
      const found = (body.content ?? []).find(
        (n: any) => n.category === options.category && (!options.predicate || options.predicate(n))
      );
      if (found) return found;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`Timed out waiting for notification category=${options.category}`);
}

export interface BootstrappedSitter extends TestAccount {
  planId: string;
  planName: string;
}

/**
 * 重跑 Journey A 驗證過的機械部分，純 API 兜出一個 VERIFIED + isOpen + 有一個上架方案的保母，
 * 給 Journey B/C/D/E 當前置條件用（不重複走 UI，UI 部分已經在 Journey A 驗證過）。
 */
export async function bootstrapVerifiedSitter(
  request: APIRequestContext,
  scenario: string,
  planNamePrefix: string
): Promise<BootstrappedSitter> {
  let sitter = await provisionAndLogin(request, scenario, 'SITTER', `Journey保母${scenario}`);
  sitter = await switchRole(request, sitter, 'SITTER');

  const kycRes = await request.post('/api/sitter/kyc', {
    headers: { ...apiAuthed(sitter), 'Idempotency-Key': randomUUID() },
    multipart: {
      idCardFront: {
        name: 'front.jpg',
        mimeType: 'image/jpeg',
        buffer: fs.readFileSync(path.join(__dirname, 'fixtures/kyc-id-front.jpg'))
      },
      selfie: {
        name: 'selfie.jpg',
        mimeType: 'image/jpeg',
        buffer: fs.readFileSync(path.join(__dirname, 'fixtures/kyc-selfie.jpg'))
      }
    }
  });
  if (!kycRes.ok()) {
    throw new Error(`kyc submit failed (${kycRes.status()}): ${await kycRes.text()}`);
  }

  const admin = await loginAsSeedAdmin(request);
  const pendingRes = await request.get('/api/admin/kyc/pending?page=0&size=50', { headers: apiAuthed(admin) });
  const pendingBody = await pendingRes.json();
  const record = pendingBody.data.content.find((r: any) => r.email === sitter.email);
  if (!record) {
    throw new Error(`pending kyc record not found for ${sitter.email}`);
  }

  const reviewRes = await request.post(`/api/admin/kyc/${record.recordId}/review`, {
    headers: { ...apiAuthed(admin), 'Idempotency-Key': randomUUID() },
    data: { action: 'APPROVE' }
  });
  if (!reviewRes.ok()) {
    throw new Error(`kyc approve failed (${reviewRes.status()}): ${await reviewRes.text()}`);
  }

  const openRes = await request.put('/api/sitter/kyc/open', { headers: apiAuthed(sitter), data: { isOpen: true } });
  if (!openRes.ok()) {
    throw new Error(`open status failed (${openRes.status()}): ${await openRes.text()}`);
  }

  const selfRes = await request.get(`/api/sitter/profile/${sitter.userId}`, { headers: apiAuthed(sitter) });
  const selfProfile = await selfRes.json();
  const profileRes = await request.put('/api/sitter/profile', {
    headers: apiAuthed(sitter),
    data: {
      displayName: `Journey保母${scenario}公開顯示名稱-${Date.now()}`,
      bio: 'journey bootstrap sitter',
      isVisible: true,
      tags: ['細心'],
      serviceAreas: [],
      version: selfProfile.version
    }
  });
  if (!profileRes.ok()) {
    throw new Error(`profile update failed (${profileRes.status()}): ${await profileRes.text()}`);
  }

  const planName = `${planNamePrefix}-${Date.now()}`;
  const planRes = await request.post('/api/sitter/plans', {
    headers: apiAuthed(sitter),
    data: {
      name: planName,
      price: 600,
      dailyCapacity: 5,
      durationMinutes: 60,
      defaultTasks: ['基本餵食'],
      applicablePetTypes: ['CAT'],
      description: '',
      isRestricted: false
    }
  });
  if (!planRes.ok()) {
    throw new Error(`plan create failed (${planRes.status()}): ${await planRes.text()}`);
  }
  const planBody = await planRes.json();

  return { ...sitter, planId: planBody.data.id, planName };
}

/**
 * 輪詢 GET /api/orders/{orderId} 直到狀態進入期望集合。
 *
 * 實測發現：飼主端送出預約後，緊接著（約 1 秒內）用保母帳號查 GET /api/orders/sitter
 * 有機率查到空陣列，即使該筆訂單已經 201 建立成功——正式站底層連線池/查詢路徑在極短時間
 * 內偶爾有讀寫不同步的情形。不能假設「動作 API 回應 200/201 後，下一個查詢就一定看得到」，
 * 跨模組 journey 的每個狀態轉移之後都應該像這樣 poll 過一輪再繼續，不要直接切換身份就查列表頁。
 */
export async function awaitOrderStatus(
  request: APIRequestContext,
  account: TestAccount,
  orderId: string,
  expectedStatuses: string[],
  timeoutMs = 15000
): Promise<any> {
  const intervalMs = 500;
  const deadline = Date.now() + timeoutMs;
  let last: any;
  while (Date.now() < deadline) {
    const res = await request.get(`/api/orders/${orderId}`, { headers: apiAuthed(account) });
    if (res.ok()) {
      last = await res.json();
      if (expectedStatuses.includes(last.status)) return last;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(
    `Timed out waiting for order ${orderId} status in [${expectedStatuses.join(',')}], last=${JSON.stringify(last)}`
  );
}

/** 比照 client-booking.spec.ts 的截圖慣例，附進 HTML 報告 */
export async function attachScreenshot(page: Page, testInfo: TestInfo, name: string) {
  await testInfo.attach(name, { body: await page.screenshot(), contentType: 'image/png' });
}
