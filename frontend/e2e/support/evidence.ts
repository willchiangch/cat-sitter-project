import fs from 'fs';
import path from 'path';
import { test, expect, type Page } from '@playwright/test';

/**
 * E2E 測試報告的「證據」統一出口。規則：
 * 1. 每個 UI 步驟一律用 step()，步驟結束（斷言通過後）自動截圖；步驟失敗也會截一張 FAIL- 開頭的圖。
 *    Playwright 內建 screenshot:'on' 只會截 `page` fixture，browser.newContext() 開出來的
 *    ownerPage/sitterPage/anonPage 失敗時完全沒有截圖，這裡補上。
 * 2. 純 API 步驟用 apiStep()，回傳值會以 JSON 附進報告（token/password 會遮蔽）。
 * 3. 步驟中間需要額外截圖時用 snap()，不要再直接 testInfo.attach(page.screenshot())。
 * 4. 頁面上的 window.alert/confirm 一律用 trackDialogs() 接手：原生對話框不會出現在截圖裡，
 *    本專案操作結果幾乎都用 alert() 呈現，不接手的話「操作後截圖」只會截到操作前的畫面。
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

const BUSY_TEXT = /^(處理中|送出中|儲存中|上傳中|載入中)\.{0,3}$/;
const ECHO_ID = '__e2e_dialog_echo';

/** 截圖前等畫面穩定：網路靜止（上限 3 秒，避免輪詢頁面卡死）、字型載入、按鈕不在「處理中...」 */
async function settle(page: Page) {
  await page.waitForLoadState('domcontentloaded');
  await page.waitForLoadState('networkidle', { timeout: 3000 }).catch(() => {});
  await expect(page.getByText(BUSY_TEXT))
    .toHaveCount(0, { timeout: 10000 })
    .catch(() => {});
  await page.evaluate(() => document.fonts.ready).catch(() => {});
}

type EvidenceKind = 'screenshot' | 'api' | 'dialog';

/** 落地成檔 + attach by path + 追加 manifest，回傳檔案路徑 */
async function record(
  kind: EvidenceKind,
  label: string,
  ext: string,
  body: Buffer | string,
  page?: Page
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
    at: new Date().toISOString()
  };
  fs.appendFileSync(path.join(dir, 'manifest.jsonl'), JSON.stringify(line) + '\n');
  return file;
}

export async function snap(page: Page, label: string) {
  if (page.isClosed()) return;
  await settle(page);
  const body = await page.screenshot({ fullPage: true, animations: 'disabled', caret: 'hide' });
  await record('screenshot', label, 'png', body, page);
  // 對話框回顯只屬於「這一張」截圖，截完就移除，避免下一張截到過期訊息
  await page.evaluate((id) => document.getElementById(id)?.remove(), ECHO_ID).catch(() => {});
}

export async function step<T>(label: string, page: Page, body: () => Promise<T>): Promise<T> {
  return test.step(label, async () => {
    let result: T;
    try {
      result = await body();
    } catch (err) {
      await snap(page, `FAIL-${label}`).catch(() => {});
      throw err;
    }
    await snap(page, label);
    return result;
  });
}

const SENSITIVE_KEY = /token|password|secret/i;

export async function apiStep<T>(label: string, body: () => Promise<T>): Promise<T> {
  return test.step(label, async () => {
    const result = await body();
    const json = JSON.stringify(result ?? null, (k, v) => (SENSITIVE_KEY.test(k) ? '***' : v), 2);
    await record('api', label, 'json', json);
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
