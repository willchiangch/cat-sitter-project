import fs from 'fs';
import path from 'path';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { expect } from '@playwright/test';
import type { APIRequestContext, APIResponse, Locator, Page } from '@playwright/test';
import { apiStep } from '../support/evidence';

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

/**
 * Cloud Run 冷啟動保護：服務縮到 0 時，多個 worker 同時打第一發 request，
 * 超出可用 instance 的那幾發會被 Google Frontend 直接 abort 成 429「Rate exceeded.」或 503，
 * body 是純文字/HTML。這種 request 根本沒進 container，重送是安全的。
 *
 * 後端自己也會回 429：GlobalExceptionHandler 對 LockedException 回 JSON 429 ACCOUNT_LOCKED，
 * AuthService 有 OTP_LOCKED / OTP_RESEND_TOO_SOON。這些是真實業務結果（帳號鎖 10 分鐘），
 * 重試只會多等 21 秒還把原因藏起來，所以 **body 是 JSON 的 429/503 一律不重試、原樣回傳**。
 * 只用在每個 journey 最前面的 bootstrap 請求（provision / login），不要拿去包業務斷言。
 */
const COLD_START_STATUSES = [429, 503];
const COLD_START_DELAYS_MS = [3000, 6000, 12000];

async function isFrontendAbort(res: APIResponse): Promise<boolean> {
  if (!COLD_START_STATUSES.includes(res.status())) return false;
  if ((res.headers()['content-type'] ?? '').includes('application/json')) return false;
  try {
    JSON.parse(await res.text());
    return false;
  } catch {
    return true;
  }
}

async function postWithColdStartRetry(
  request: APIRequestContext,
  url: string,
  options: Parameters<APIRequestContext['post']>[1]
) {
  let res = await request.post(url, { timeout: 60000, ...options });
  for (const delay of COLD_START_DELAYS_MS) {
    if (!(await isFrontendAbort(res))) break;
    await new Promise((resolve) => setTimeout(resolve, delay));
    res = await request.post(url, { timeout: 60000, ...options });
  }
  return res;
}

/**
 * Firebase Hosting → Cloud Run 這段（GFE）的暫時性錯誤：502/503/504 + HTML 錯誤頁（"The server encountered a
 * temporary error … try again in 30 seconds"），request 根本沒進 container（.e2e-runs/20261024-1939 F2：
 * confirm 502 只花 209ms，Cloud Run 沒有任何 request log）。判斷比照 isFrontendAbort：
 * **body 是 JSON 的一律不重試**（那是後端自己的錯誤，重試只會把真正的原因藏起來）。
 * 只包 bootstrapVerifiedSitter / bootstrapConfirmedOrder 的前置 API，不要拿去包業務斷言。
 */
const GATEWAY_ERROR_STATUSES = [502, 503, 504];
const GATEWAY_RETRY_DELAYS_MS = [2000, 4000];

async function isGatewayError(res: APIResponse): Promise<boolean> {
  if (!GATEWAY_ERROR_STATUSES.includes(res.status())) return false;
  if ((res.headers()['content-type'] ?? '').includes('application/json')) return false;
  try {
    JSON.parse(await res.text());
    return false;
  } catch {
    return true;
  }
}

/**
 * 前置 API 遇到 GFE 錯誤頁時退避重試。
 * alreadyApplied：非冪等的狀態轉移（confirm、verify-payment）若第一發其實有進後端、只是回應在 GFE 斷掉，
 * 重試會拿到 409；這時查訂單已在目標狀態就視為成功（回傳最後一個 409 response，呼叫方用 ok 判斷）。
 */
async function withGatewayRetry(
  send: () => Promise<APIResponse>,
  alreadyApplied?: () => Promise<boolean>
): Promise<{ res: APIResponse; ok: boolean }> {
  let res = await send();
  let retried = false;
  for (const delay of GATEWAY_RETRY_DELAYS_MS) {
    if (!(await isGatewayError(res))) break;
    await new Promise((resolve) => setTimeout(resolve, delay));
    res = await send();
    retried = true;
  }
  if (res.ok()) return { res, ok: true };
  if (retried && res.status() === 409 && alreadyApplied && (await alreadyApplied())) {
    return { res, ok: true };
  }
  return { res, ok: false };
}

/** 訂單已在 expectedStatus（給 withGatewayRetry 的 alreadyApplied 用，含讀取延遲的短輪詢） */
async function orderAlreadyIn(
  request: APIRequestContext,
  account: TestAccount,
  orderId: string,
  expectedStatus: string
): Promise<boolean> {
  return awaitOrderStatus(request, account, orderId, [expectedStatus], 5000).then(
    () => true,
    () => false
  );
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

  const provisionRes = await postWithColdStartRetry(
    request,
    '/api/internal/test-data/provision-account',
    {
      headers: { 'X-Internal-Secret': INTERNAL_SECRET },
      data: { email, password: TEST_PASSWORD, fullName, role }
    }
  );
  if (!provisionRes.ok()) {
    throw new Error(
      `provision-account failed (${provisionRes.status()}): ${await provisionRes.text()}`
    );
  }
  const { userId } = await provisionRes.json();

  const loginRes = await postWithColdStartRetry(request, '/api/auth/login', {
    data: { email, password: TEST_PASSWORD }
  });
  if (!loginRes.ok()) {
    throw new Error(
      `login failed after provisioning (${loginRes.status()}): ${await loginRes.text()}`
    );
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
  return {
    ...account,
    accessToken: body.accessToken,
    refreshToken: body.refreshToken ?? account.refreshToken
  };
}

/**
 * 用既有共用種子管理員帳號（admin@test.com）登入，取得真實 token。
 * 全系統只有這唯一固定的管理員帳號，不像飼主/保母走 @e2e-journey.test 動態建立。
 */
export async function loginAsSeedAdmin(request: APIRequestContext): Promise<TestAccount> {
  const res = await postWithColdStartRetry(request, '/api/auth/login', {
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
      // GET /api/notifications 回傳 { code, message, data: { content, page, ... } }，
      // 不是把 content 攤平在最外層，body.content 永遠是 undefined。
      const found = (body.data?.content ?? []).find(
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

  // 重試沿用同一把 Idempotency-Key：第一發若其實有進後端，重送拿到的是同一筆結果
  const kycKey = randomUUID();
  const { res: kycRes, ok: kycResOk } = await withGatewayRetry(() =>
    request.post('/api/sitter/kyc', {
      headers: { ...apiAuthed(sitter), 'Idempotency-Key': kycKey },
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
    })
  );
  if (!kycResOk) {
    throw new Error(`kyc submit failed (${kycRes.status()}): ${await kycRes.text()}`);
  }

  const admin = await loginAsSeedAdmin(request);
  const { res: pendingRes } = await withGatewayRetry(() =>
    request.get('/api/admin/kyc/pending?page=0&size=50', { headers: apiAuthed(admin) })
  );
  const pendingBody = await pendingRes.json();
  const record = pendingBody.data.content.find((r: any) => r.email === sitter.email);
  if (!record) {
    throw new Error(`pending kyc record not found for ${sitter.email}`);
  }

  // 重試沿用同一把 Idempotency-Key：第一發若其實有進後端，重送拿到的是同一筆結果
  const reviewKey = randomUUID();
  const { res: reviewRes, ok: reviewResOk } = await withGatewayRetry(() =>
    request.post(`/api/admin/kyc/${record.recordId}/review`, {
      headers: { ...apiAuthed(admin), 'Idempotency-Key': reviewKey },
      data: { action: 'APPROVE' }
    })
  );
  if (!reviewResOk) {
    throw new Error(`kyc approve failed (${reviewRes.status()}): ${await reviewRes.text()}`);
  }

  const { res: openRes, ok: openResOk } = await withGatewayRetry(() =>
    request.put('/api/sitter/kyc/open', { headers: apiAuthed(sitter), data: { isOpen: true } })
  );
  if (!openResOk) {
    throw new Error(`open status failed (${openRes.status()}): ${await openRes.text()}`);
  }

  const { res: selfRes } = await withGatewayRetry(() =>
    request.get(`/api/sitter/profile/${sitter.userId}`, { headers: apiAuthed(sitter) })
  );
  const selfProfile = await selfRes.json();
  const { res: profileRes, ok: profileResOk } = await withGatewayRetry(() =>
    request.put('/api/sitter/profile', {
      headers: apiAuthed(sitter),
      data: {
        displayName: `Journey保母${scenario}公開顯示名稱-${Date.now()}`,
        bio: 'journey bootstrap sitter',
        isVisible: true,
        tags: ['細心'],
        serviceAreas: [],
        version: selfProfile.version
      }
    })
  );
  if (!profileResOk) {
    throw new Error(`profile update failed (${profileRes.status()}): ${await profileRes.text()}`);
  }

  const planName = `${planNamePrefix}-${Date.now()}`;
  const { res: planRes, ok: planResOk } = await withGatewayRetry(() =>
    request.post('/api/sitter/plans', {
      headers: apiAuthed(sitter),
      data: {
        name: planName,
        price: 600,
        durationMinutes: 60,
        defaultTasks: ['基本餵食'],
        applicablePetTypes: ['CAT'],
        description: '',
        isRestricted: false
      }
    })
  );
  if (!planResOk) {
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

export interface ConfirmedOrderContext {
  sitter: BootstrappedSitter;
  owner: TestAccount;
  orderId: string;
}

/**
 * 用共用種子管理員把保母的訂閱等級改成 tier（POST /api/admin/subscriptions/{sitterId}）。
 * 議價報價有調整金額時 PlanGatingAspect 要求 PRO 以上，新保母沒有 subscriptions 資料列視為 FREE。
 */
export async function setSitterPlanTier(
  request: APIRequestContext,
  sitterId: string,
  tier: 'FREE' | 'BASIC' | 'PRO' | 'ULTIMATE'
): Promise<{ planTier: string }> {
  const admin = await loginAsSeedAdmin(request);
  const { res, ok } = await withGatewayRetry(() =>
    request.post(`/api/admin/subscriptions/${sitterId}`, {
      headers: apiAuthed(admin),
      data: { planTier: tier }
    })
  );
  if (!ok) {
    throw new Error(`set subscription failed (${res.status()}): ${await res.text()}`);
  }
  const body = await res.json();
  return { planTier: body.planTier };
}

/**
 * 純 API 兜出一筆 PENDING 訂單（VERIFIED 保母 + 飼主 + 一隻貓 + 單一方案 $600/天）。
 * Journey B 議價分支從這裡接手走 UI 報價；bootstrapConfirmedOrder 接著原價接單到 CONFIRMED。
 */
export async function bootstrapPendingOrder(
  request: APIRequestContext,
  scenario: string,
  planNamePrefix: string,
  datesOverride?: string[]
): Promise<ConfirmedOrderContext> {
  const sitter = await bootstrapVerifiedSitter(request, scenario, planNamePrefix);
  const owner = await provisionAndLogin(request, scenario, 'OWNER', `Journey飼主${scenario}`);

  const { res: petRes, ok: petResOk } = await withGatewayRetry(() =>
    request.post('/api/pets', {
      headers: apiAuthed(owner),
      data: { name: `Journey測試貓${scenario}`, species: 'CAT' }
    })
  );
  if (!petResOk) {
    throw new Error(`pet create failed (${petRes.status()}): ${await petRes.text()}`);
  }
  const petBody = await petRes.json();
  const petId = petBody.data.id;

  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const dateStr = tomorrow.toISOString().split('T')[0];
  // Journey D 退款分支需要「原始訂單天數 > 修改後天數」才能觸發退款差額，
  // 用 datesOverride 讓呼叫方兜出多天的原始訂單，預設仍是單天（沿用 Journey B/C 行為）
  const dates = datesOverride ?? [dateStr];

  // 重試沿用同一把 Idempotency-Key：第一發若其實有進後端，重送拿到的是同一筆結果
  const bookingKey = randomUUID();
  const { res: bookingRes, ok: bookingResOk } = await withGatewayRetry(() =>
    request.post('/api/orders/booking', {
      headers: { ...apiAuthed(owner), 'Idempotency-Key': bookingKey },
      // 不帶 ownerId：飼主身分由後端從 token 取得（fix-batch11 I2）
      data: {
        sitterId: sitter.userId,
        items: [{ planId: sitter.planId, dates, timesPerDay: 1, petIds: [petId] }],
        answers: [],
        expectedTotalAmount: 600 * dates.length
      }
    })
  );
  if (!bookingResOk) {
    throw new Error(`booking failed (${bookingRes.status()}): ${await bookingRes.text()}`);
  }
  const { orderId } = await bookingRes.json();
  await awaitOrderStatus(request, owner, orderId, ['PENDING']);

  return { sitter, owner, orderId };
}

/**
 * 純 API 重跑 Journey A（保母上架）+ Journey B（下單到付款）驗證過的機械部分，
 * 兜出一筆 CONFIRMED 訂單，給 Journey C/D/E 當前置條件用，不重複走 UI。
 */
export async function bootstrapConfirmedOrder(
  request: APIRequestContext,
  scenario: string,
  planNamePrefix: string,
  datesOverride?: string[]
): Promise<ConfirmedOrderContext> {
  const { sitter, owner, orderId } = await bootstrapPendingOrder(
    request,
    scenario,
    planNamePrefix,
    datesOverride
  );

  const { res: confirmRes, ok: confirmResOk } = await withGatewayRetry(
    () =>
      // 身分由後端從 token 取得，不帶 sitterId（fix-batch11 I1）
      request.post(`/api/orders/${orderId}/confirm`, {
        headers: apiAuthed(sitter)
      }),
    () => orderAlreadyIn(request, owner, orderId, 'PENDING_PAYMENT')
  );
  if (!confirmResOk) {
    throw new Error(`confirm failed (${confirmRes.status()}): ${await confirmRes.text()}`);
  }
  await awaitOrderStatus(request, owner, orderId, ['PENDING_PAYMENT']);

  // 重試沿用同一把 Idempotency-Key：第一發若其實有進後端，重送拿到的是同一筆結果
  const paymentKey = randomUUID();
  const { res: paymentRes, ok: paymentResOk } = await withGatewayRetry(() =>
    request.post(`/api/orders/${orderId}/payment-proof`, {
      headers: { ...apiAuthed(owner), 'Idempotency-Key': paymentKey },
      multipart: {
        file: {
          name: 'proof.jpg',
          mimeType: 'image/jpeg',
          buffer: fs.readFileSync(path.join(__dirname, 'fixtures/kyc-selfie.jpg'))
        },
        lastFive: '12345',
        disclaimerAgreed: 'true'
      }
    })
  );
  if (!paymentResOk) {
    throw new Error(`payment-proof failed (${paymentRes.status()}): ${await paymentRes.text()}`);
  }
  await awaitOrderStatus(request, sitter, orderId, ['PAID']);

  const { res: verifyRes, ok: verifyResOk } = await withGatewayRetry(
    () => request.post(`/api/orders/${orderId}/verify-payment`, { headers: apiAuthed(sitter) }),
    () => orderAlreadyIn(request, owner, orderId, 'CONFIRMED')
  );
  if (!verifyResOk) {
    throw new Error(`verify-payment failed (${verifyRes.status()}): ${await verifyRes.text()}`);
  }
  await awaitOrderStatus(request, owner, orderId, ['CONFIRMED']);

  return { sitter, owner, orderId };
}

/**
 * 通用輪詢：重複執行 fn() 直到回傳 truthy 或逾時。
 *
 * 跟 awaitOrderStatus 一樣的教訓，但這次踩在完全不同的模組（照護記事本）上：
 * 保母 PUT 記事本回應 200 後，飼主帳號緊接著（同一 test 內，前後不到 50ms）GET 同一份
 * 記事本查到的還是舊內容，隔了約 2-3 秒後再查才看得到新內容。跟訂單那次合併起來看，
 * 這比較像是正式環境本身的特性（可能跟 Cloud Run 多執行個體 + Supabase 連線池有關，
 * 沒有再往下深究），不是單一端點的個案——**跨模組 journey 只要涉及「不同身份/session
 * 幾乎同時讀寫同一筆資料」，都要假設有幾秒的不一致視窗，用 poll 代替立即斷言。**
 */
export async function pollUntil<T>(
  fn: () => Promise<T>,
  predicate: (value: T) => boolean,
  timeoutMs = 15000,
  intervalMs = 500
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T;
  do {
    last = await fn();
    if (predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  } while (Date.now() < deadline);
  throw new Error(`pollUntil timed out after ${timeoutMs}ms, last value=${JSON.stringify(last)}`);
}

/**
 * 付款憑證預覽 (SD-007 §4.7，B1 迴歸)：憑證是私有 GCS 物件，<img> 只能是後端簽發的 V4 簽名網址
 * (https://storage.googleapis.com/<bucket>/payment-proofs/...?X-Goog-...&X-Goog-Signature=...)，
 * 不能是不帶簽名的公開路徑；而且圖片要真的載入成功（私有物件若誤用公開網址會 403 變破圖）。
 */
export async function expectSignedPaymentProof(
  scope: Locator | Page,
  label = '付款憑證預覽為私有物件簽名網址'
) {
  await expectSignedProofImage(scope, 'payment-proof-preview', 'payment-proofs', label);
}

/**
 * C4：退款憑證同樣是私有物件 + 簽名網址（SD-016 §2.5），不可是保母手填的任意外部網址
 */
export async function expectSignedRefundProof(
  scope: Locator | Page,
  label = '退款憑證預覽為私有物件簽名網址'
) {
  await expectSignedProofImage(scope, 'refund-proof-preview', 'refund-proofs', label);
}

/** 證據用：只留 path 與不敏感的 X-Goog-Algorithm / X-Goog-Date / X-Goog-Expires，簽名與 Credential 一律遮蔽 */
function redactSignedUrl(src: string) {
  const url = new URL(src);
  const keep = ['X-Goog-Algorithm', 'X-Goog-Date', 'X-Goog-Expires'];
  const query: Record<string, string> = {};
  for (const [k, v] of url.searchParams) query[k] = keep.includes(k) ? v : '***';
  return { origin: url.origin, path: url.pathname, query };
}

async function expectSignedProofImage(
  scope: Locator | Page,
  testId: string,
  folder: string,
  label: string
) {
  const img = scope.getByTestId(testId);
  await expect(img).toHaveAttribute(
    'src',
    new RegExp(`^https://storage\\.googleapis\\.com/[^?]+/${folder}/[^?]+\\?.*X-Goog-Signature=`)
  );
  await expect
    .poll(() =>
      img.evaluate(
        (el) => (el as HTMLImageElement).complete && (el as HTMLImageElement).naturalWidth > 0
      )
    )
    .toBe(true);
  // 驗收 S1 / S3：簽名網址留 JSON 證據（遮簽名），並直接證明物件是私有的——同一個物件不帶簽名打要 403。
  // 物件此刻剛載入成功，必定存在，所以 403 只能代表「無權限」，不會是物件已被清掉
  await apiStep(label, async () => {
    const src = (await img.getAttribute('src')) ?? '';
    const unsignedUrl = src.split('?')[0];
    const unsigned = await img.page().request.get(unsignedUrl);
    expect(unsigned.status(), `不帶簽名打 ${unsignedUrl} 應被拒絕`).toBe(403);
    return {
      signedSrc: redactSignedUrl(src),
      imageLoaded: true,
      unsignedRequest: { url: unsignedUrl, status: unsigned.status() }
    };
  });
}

/**
 * C1 迴歸 (X2)：匿名頁面不得在背景以種子帳號自動登入。回傳的 count 是此 page 送出的
 * POST /api/auth/login 次數；anonymousProfileCalls 記錄公開頁 API 是否帶了 Authorization
 */
export function trackLoginRequests(page: Page) {
  const tracker = { count: 0, authorizedProfileCalls: 0, profileCalls: 0 };
  page.on('request', (req) => {
    const pathname = new URL(req.url()).pathname;
    if (req.method() === 'POST' && pathname === '/api/auth/login') tracker.count++;
    if (req.method() === 'GET' && pathname.startsWith('/api/sitter/profile/')) {
      tracker.profileCalls++;
      if (req.headers()['authorization']) tracker.authorizedProfileCalls++;
    }
  });
  return tracker;
}

/** 匿名頁面渲染完成後呼叫：沒有送出登入請求、公開頁 API 沒帶 token、瀏覽器內沒有任何 token */
export async function expectStillAnonymous(
  page: Page,
  tracker: ReturnType<typeof trackLoginRequests>
) {
  expect(tracker.count).toBe(0);
  expect(tracker.profileCalls).toBeGreaterThan(0);
  expect(tracker.authorizedProfileCalls).toBe(0);
  expect(await page.evaluate(() => localStorage.getItem('accessToken'))).toBeNull();
}
