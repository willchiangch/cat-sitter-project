import fs from 'fs';
import path from 'path';
import { test, expect, type Page, type Request } from '@playwright/test';

/**
 * E2E 測試報告的「證據」統一出口。規則：
 * 1. 每個 UI 步驟一律用 step()，步驟結束（斷言通過後）自動截圖；步驟失敗也會截一張 FAIL- 開頭的圖。
 *    Playwright 內建 screenshot:'on' 只會截 `page` fixture，browser.newContext() 開出來的
 *    ownerPage/sitterPage/anonPage 失敗時完全沒有截圖，這裡補上。
 * 2. 純 API 步驟用 apiStep()，回傳值會以 JSON 附進報告（token/password/secret 的非空值會遮蔽，null 保留）。
 *    UI 步驟 step() 的 body 若回傳非 undefined 的值（例如量到的對比度、computed style、boundingBox），
 *    同樣套用遮蔽規則以 JSON 附在該步驟截圖之後（fix-batch14 N4：過去 step() 丟掉回傳值，K9/K10 的 ratio 不進報告）。
 *    無法序列化的回傳值（Locator、循環參照）不附，manifest 記一筆 note.unserializable。
 * 3. 步驟中間需要額外截圖時用 snap()，不要再直接 testInfo.attach(page.screenshot())。
 * 4. 頁面上的 window.alert/confirm 一律用 trackDialogs() 接手：原生對話框不會出現在截圖裡，
 *    本專案操作結果幾乎都用 alert() 呈現，不接手的話「操作後截圖」只會截到操作前的畫面。
 * 5. 截圖前會等可見的 <img> 載入完成（上限 5 秒）。逾時仍在載入的 src 記在 manifest 的
 *    note.imagesPending，載入失敗（404/無權限，naturalWidth=0）的記在 note.imagesBroken，
 *    驗收時看到截圖缺圖，先查這兩個欄位判斷是產品 bug 還是截太早。
 *
 * 所有附件依呼叫順序自動編號（01-、02-…），報告裡的順序 = 實際發生順序。
 * 檔案同時落地在 test-results/<test>/evidence/，並寫一份 manifest.jsonl（給驗收 agent 逐步比對 TS 文件用）。
 */

const counters = new WeakMap<object, number>();

function nextIndex(): string {
  const info = test.info();
  const n = (counters.get(info) ?? 0) + 1;
  counters.set(info, n);
  return String(n).padStart(2, '0');
}

// 不用 ^…$ 全錨定，才抓得到「載入 KYC 申請詳情中...」「預約送出中，請稍候...」這類帶前綴的字；
// 但限制整段文字 ≤ 約 20 字前綴 + 關鍵字，避免整段說明文字或狀態徽章被誤判成忙碌中
const BUSY_TEXT = /^.{0,20}(處理中|送出中|儲存中|上傳中|載入.{0,12}中)[，,]?\s*(請稍候)?\.{0,3}$/;
const ECHO_ID = '__e2e_dialog_echo';

/**
 * SPA 裡 waitForLoadState('networkidle') 沒有作用：它只看頁面載入的 lifecycle，
 * 頁面早就到過 networkidle，之後的 XHR 完全不會等（實測 A-04 兩張截圖只差 74ms、詳情 API 還沒送出）。
 * 改成以 page 為單位追蹤進行中的 xhr/fetch。step()/trackDialogs() 會自動掛上，不需要手動呼叫。
 */
const inflight = new WeakMap<Page, Map<Request, number>>();
const NETWORK_TYPES = new Set(['xhr', 'fetch']);
// 超過這個時間還沒結束的 request（長輪詢、被 SW 吃掉沒有 finished 事件）不再視為「進行中」
const STALE_REQUEST_MS = 10000;

export function trackNetwork(page: Page) {
  if (inflight.has(page)) return;
  const pending = new Map<Request, number>();
  inflight.set(page, pending);
  page.on('request', (r) => {
    if (NETWORK_TYPES.has(r.resourceType())) pending.set(r, Date.now());
  });
  page.on('requestfinished', (r) => pending.delete(r));
  page.on('requestfailed', (r) => pending.delete(r));
}

function pendingCount(page: Page): number {
  const pending = inflight.get(page);
  if (!pending) return 0;
  const now = Date.now();
  let n = 0;
  for (const startedAt of pending.values()) if (now - startedAt < STALE_REQUEST_MS) n++;
  return n;
}

/** 等到進行中的 xhr/fetch 歸零並維持 quietMs（上限 maxMs，避免輪詢頁面卡死） */
async function waitForNetworkQuiet(page: Page, quietMs = 300, maxMs = 5000) {
  const deadline = Date.now() + maxMs;
  let quietSince = 0;
  while (Date.now() < deadline) {
    if (pendingCount(page) === 0) {
      quietSince ||= Date.now();
      if (Date.now() - quietSince >= quietMs) return;
    } else {
      quietSince = 0;
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

interface ImageStatus {
  /** 5 秒內仍未載入完成（img.complete=false）的 src */
  imagesPending?: string[];
  /** 已結束但沒有畫面（complete=true、naturalWidth=0：404、無權限、src 空字串）的 src */
  imagesBroken?: string[];
}

const IMAGE_WAIT_MS = 5000;

/**
 * 等可見的 <img> 都載入完成（上限 IMAGE_WAIT_MS）。網路靜止只看 xhr/fetch，圖片不在內，
 * 過去 A-02、B-07、C-04、D1 都截到「圖片還在載入的黑塊/空白」，分不出是 bug 還是截太早。
 * 逾時不丟錯（截圖仍有價值），但把沒載入/載入失敗的 src 回傳，寫進 manifest 的 note 欄位。
 */
async function waitForImages(page: Page): Promise<ImageStatus> {
  const allSettled = () =>
    Array.from(document.images)
      .filter((img) => img.checkVisibility())
      .every((img) => img.complete);
  await page
    .waitForFunction(allSettled, undefined, { timeout: IMAGE_WAIT_MS, polling: 100 })
    .catch(() => {});
  const status = await page
    .evaluate(() => {
      const visible = Array.from(document.images).filter((img) => img.checkVisibility());
      return {
        imagesPending: visible.filter((img) => !img.complete).map((img) => img.src),
        imagesBroken: visible
          .filter((img) => img.complete && img.naturalWidth === 0)
          .map((img) => img.src)
      };
    })
    .catch(() => ({ imagesPending: [], imagesBroken: [] }));
  const result: ImageStatus = {};
  if (status.imagesPending.length) result.imagesPending = status.imagesPending;
  if (status.imagesBroken.length) result.imagesBroken = status.imagesBroken;
  return result;
}

/** 截圖前等畫面穩定：xhr/fetch 靜止、按鈕/頁面不在「處理中...」、圖片載入、React commit 完成、字型載入 */
async function settle(page: Page): Promise<ImageStatus> {
  await page.waitForLoadState('domcontentloaded');
  await waitForNetworkQuiet(page);
  await expect(page.getByText(BUSY_TEXT).and(page.locator(`:not(#${ECHO_ID})`)))
    .toHaveCount(0, { timeout: 10000 })
    .catch(() => {});
  const images = await waitForImages(page);
  // 資料回來後 React 還要 commit + paint，等兩個 animation frame（等的是 render flush，不是固定秒數）
  await page
    .evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
        )
    )
    .catch(() => {});
  await page.evaluate(() => document.fonts.ready).catch(() => {});
  return images;
}

/**
 * fullPage 截圖對本專案無效：AppShell 是 height:100vh + overflow:hidden、內層 <main> 自己捲動，
 * 加上 global.css 的 html,body{overflow:hidden}，document 永遠只有一個 viewport 高，fold 下方拍不到。
 * 截圖前暫時解除這些限制、截完移除。
 */
const UNCLIP_CSS =
  'html,body,#root{overflow:visible!important;height:auto!important}' +
  'div:has(> main){height:auto!important;min-height:100vh;overflow:visible!important}' +
  'main{overflow:visible!important;flex:none!important}';

type EvidenceKind = 'screenshot' | 'api' | 'dialog' | 'data';

/** 落地成檔 + attach by path + 追加 manifest，回傳檔案路徑 */
async function record(
  kind: EvidenceKind,
  label: string,
  ext: string,
  body: Buffer | string,
  page?: Page,
  note?: ImageStatus | { unserializable: string }
) {
  const info = test.info();
  const idx = nextIndex();
  const safe = label.replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 80);
  const dir = info.outputPath('evidence');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${idx}-${safe}.${ext}`);
  fs.writeFileSync(file, body);
  const contentType =
    ext === 'png' ? 'image/png' : ext === 'json' ? 'application/json' : 'text/plain';
  await info.attach(`${idx}-${label}`, { path: file, contentType });
  const line = {
    idx,
    kind,
    label,
    file,
    url: page && !page.isClosed() ? page.url() : undefined,
    at: new Date().toISOString(),
    note: note && Object.keys(note).length ? note : undefined
  };
  fs.appendFileSync(path.join(dir, 'manifest.jsonl'), JSON.stringify(line) + '\n');
  return file;
}

export async function snap(page: Page, label: string) {
  if (page.isClosed()) return;
  const imageStatus = await settle(page);
  const unclip = await page.addStyleTag({ content: UNCLIP_CSS }).catch(() => null);
  let body: Buffer;
  try {
    body = await page.screenshot({ fullPage: true, animations: 'disabled', caret: 'hide' });
  } finally {
    await unclip?.evaluate((n) => (n as Element).remove()).catch(() => {});
  }
  await record('screenshot', label, 'png', body, page, imageStatus);
  // 對話框回顯只屬於「這一張」截圖，截完就移除，避免下一張截到過期訊息
  await page.evaluate((id) => document.getElementById(id)?.remove(), ECHO_ID).catch(() => {});
}

export async function step<T>(label: string, page: Page, body: () => Promise<T>): Promise<T> {
  trackNetwork(page);
  return test.step(label, async () => {
    let result: T;
    try {
      result = await body();
    } catch (err) {
      await snap(page, `FAIL-${label}`).catch(() => {});
      throw err;
    }
    await snap(page, label);
    if (result !== undefined) {
      await recordStepResult(label, result, page);
    }
    return result;
  });
}

/** step() 的回傳值：可序列化 → JSON 附件（kind=data）；不可序列化 → 只在 manifest 記原因 */
async function recordStepResult(label: string, result: unknown, page: Page) {
  let json: string;
  try {
    json = toEvidenceJson(result, true);
    // 函式 / symbol 之類 JSON.stringify 直接回 undefined
    if (json === undefined) throw new Error(`回傳值型別 ${typeof result} 無法轉成 JSON`);
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    await record('data', `${label}-回傳值`, 'txt', `無法序列化：${reason}`, page, {
      unserializable: reason
    });
    return;
  }
  await record('data', `${label}-回傳值`, 'json', json, page);
}

const SENSITIVE_KEY = /token|password|secret|authorization/i;
// key 不敏感的字串裡夾帶的 JWT（例如 authHeaders: ['Bearer eyJ…']）也遮掉；mock spec 的假 token 不是 JWT 形狀，保留可讀
const JWT_LIKE = /eyJ[\w-]+\.[\w-]+\.[\w-]*/g;

/**
 * 敏感 key 底下的值一律遮蔽，但 null / undefined / 空字串原樣保留（驗收 A6：過去整個 tokens 物件被遮成
 * "***"，「token 已清成 null」從證據看不出來）。物件 / 陣列往下遞迴，裡面每個非空葉值都遮，
 * 所以 { token: { value: '真 token' } } 這種巢狀結構也不會漏出來。
 */
function maskSensitive(value: unknown): unknown {
  if (value === null || value === undefined || value === '') return value;
  if (Array.isArray(value)) return value.map(maskSensitive);
  if (typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, maskSensitive(v)])
    );
  }
  return '***';
}

/**
 * apiStep / step 共用的 JSON 序列化：敏感 key 遮蔽、字串內的 JWT 遮蔽。
 * dataOnly（step 用）：Playwright 的 Locator / Page 等類別實例不是資料，直接丟錯（由 recordStepResult 略過），
 * 避免把整個 frame 樹寫進報告；apiStep 維持原行為不檢查。
 */
function toEvidenceJson(value: unknown, dataOnly = false): string {
  return JSON.stringify(
    value,
    (k, v) => {
      if (dataOnly && v && typeof v === 'object' && !Array.isArray(v)) {
        const ctor = (v as object).constructor?.name;
        if (ctor && ctor !== 'Object') {
          throw new Error(`回傳值含非資料物件 ${ctor}${k ? `（key: ${k}）` : ''}`);
        }
      }
      return SENSITIVE_KEY.test(k)
        ? maskSensitive(v)
        : typeof v === 'string'
          ? v.replace(JWT_LIKE, 'eyJ***')
          : v;
    },
    2
  );
}

export async function apiStep<T>(label: string, body: () => Promise<T>): Promise<T> {
  return test.step(label, async () => {
    let result: T;
    try {
      result = await body();
    } catch (err) {
      // 失敗也要留證據：過去 apiStep 一失敗 evidence/ 什麼都沒有，只能翻 trace
      const error = err instanceof Error ? { message: err.message, stack: err.stack } : String(err);
      await record('api', `FAIL-${label}`, 'json', JSON.stringify({ error }, null, 2)).catch(
        () => {}
      );
      throw err;
    }
    await record('api', label, 'json', toEvidenceJson(result ?? null));
    return result;
  });
}

export interface DialogRecord {
  type: string;
  message: string;
}

/**
 * 取代 page.on('dialog', d => d.accept())。
 * - accept 所有對話框（confirm 閘控真實邏輯，不 accept 就是無效點擊）
 * - 訊息以文字附件進報告，並回顯到 DOM 右上角，讓下一張 snap 看得到操作結果
 * - 回傳的陣列給 expectDialog() 斷言用：過去 alert('操作失敗') 也會被默默 accept，測試完全分不出成功/失敗
 */
export function trackDialogs(page: Page): DialogRecord[] {
  trackNetwork(page);
  const log: DialogRecord[] = [];
  page.on('dialog', async (dialog) => {
    const entry = { type: dialog.type(), message: dialog.message() };
    log.push(entry);
    await dialog.accept();
    try {
      await record('dialog', `dialog-${entry.type}`, 'txt', entry.message, page);
    } catch {
      // 測試已結束才觸發的對話框，忽略
    }
    await page
      .evaluate(
        ([id, text, isError]) => {
          let el = document.getElementById(id);
          if (!el) {
            el = document.createElement('div');
            el.id = id;
            document.body.appendChild(el);
          }
          el.textContent = text;
          el.setAttribute(
            'style',
            `position:fixed;top:8px;right:8px;z-index:2147483647;max-width:420px;padding:10px 14px;` +
              `border-radius:8px;font:14px/1.4 sans-serif;color:#fff;box-shadow:0 2px 8px rgba(0,0,0,.3);` +
              `background:${isError ? '#b91c1c' : '#1e40af'}`
          );
        },
        [
          ECHO_ID,
          `[${entry.type}] ${entry.message}`,
          /失敗|錯誤|無法|error/i.test(entry.message)
        ] as const
      )
      .catch(() => {});
  });
  return log;
}

const dialogCursors = new WeakMap<DialogRecord[], number>();

/** 只比對「上一次 expectDialog 之後」新出現的對話框，避免前一步的成功訊息讓這一步假陽性 */
export async function expectDialog(log: DialogRecord[], pattern: RegExp, timeout = 15000) {
  const from = dialogCursors.get(log) ?? 0;
  await expect
    .poll(() => log.slice(from).map((d) => d.message), {
      timeout,
      message: `等待新對話框訊息符合 ${pattern}`
    })
    .toContainEqual(expect.stringMatching(pattern));
  const hit = log.findIndex((d, i) => i >= from && pattern.test(d.message));
  dialogCursors.set(log, hit + 1);
}
