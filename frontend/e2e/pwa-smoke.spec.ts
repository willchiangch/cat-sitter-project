import { test, expect, type Page } from '@playwright/test';
import { step, apiStep } from './support/evidence';

/**
 * PWA / Service Worker smoke（.e2e-runs/20261024-2325 弱化稽核建議）。
 *
 * playwright.config 全域 serviceWorkers:'block'（page.route mock 在正式 build 才可靠），代價是其他 spec 完全不經過
 * Workbox 的 precache / NavigationRoute。部署後若 SW 回的是舊 index.html，引用的 hash chunk 已經不在伺服器上，
 * Firebase Hosting 的 SPA rewrite 會對缺檔回 200 + index.html，瀏覽器拿 HTML 當 JS 執行 → 白畫面，CI 抓不到。
 *
 * 這支只開公開的 /login（不會觸發種子帳號自動登入、不需要任何業務 API mock），驗證：
 * 1. 正式 build 註冊 SW 成功並啟用（沒有 clientsClaim，首次載入不接管，reload 後才接管）
 * 2. reload 後整頁導覽由 SW 回應（NavigationRoute），畫面正常
 * 3. SW 回的 index.html 引用的每個 JS / CSS 都能直接從伺服器（不經 SW）取得，且 Content-Type 正確、不是 HTML
 * 4. /api 請求不經 SW（vite.config.ts workbox 註解：SW 接手 /api 會讓所有 page.route mock 失效、也可能快取 API）
 *
 * SW 只在正式 build 註冊（dev server 沒有 SW），所以只在有 BASE_URL 時執行（CI 正式站 / 本機 vite preview），
 * 由 playwright.config 的 testIgnore 控制，不在 spec 內 skip。
 */
test.use({ serviceWorkers: 'allow' });

const swState = (page: Page) =>
  page.evaluate(async () => {
    const reg = await navigator.serviceWorker.getRegistration();
    return {
      scope: reg?.scope ?? null,
      active: reg?.active?.state ?? null,
      scriptURL: reg?.active?.scriptURL ?? null,
      controlled: !!navigator.serviceWorker.controller
    };
  });

/** index.html 引用的同源 JS / CSS（module script、modulepreload、stylesheet） */
function referencedAssets(html: string, origin: string): string[] {
  const urls = new Set<string>();
  const tagRe = /<(script|link)\b[^>]*>/gi;
  for (const [tag] of html.matchAll(tagRe)) {
    const src = /\b(?:src|href)="([^"]+)"/i.exec(tag)?.[1];
    if (!src) continue;
    const isScript = /^<script/i.test(tag);
    const isAssetLink = /\brel="(?:modulepreload|stylesheet)"/i.test(tag);
    if (!isScript && !isAssetLink) continue;
    const url = new URL(src, origin);
    if (url.origin === origin) urls.add(url.href);
  }
  return [...urls];
}

test.describe('PWA smoke：Service Worker 與 precache 的 index.html', () => {
  test('SW 接管後 reload 由 SW 回 index.html，引用的 chunk 都存在，/api 不經 SW', async ({
    page
  }) => {
    await step('首次開 /login：SW 註冊並啟用', page, async () => {
      await page.goto('/login');
      await expect(page.getByTestId('login-email-input')).toBeVisible();
      // 沒有 clientsClaim：首次載入的頁面不會被接管，SW 啟用後下一次導覽才經過它
      await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
      const sw = await swState(page);
      expect(sw.active).toBe('activated');
      expect(sw.scriptURL).toMatch(/\/sw\.js$/);
    });

    const navigation = await step(
      'reload：整頁導覽由 SW（precache 的 index.html）回應，登入頁正常渲染',
      page,
      async () => {
        const response = await page.reload();
        expect(response, 'reload 的導覽回應').not.toBeNull();
        expect(response!.fromServiceWorker(), '導覽應由 SW 回應').toBe(true);
        expect(response!.status()).toBe(200);
        await expect(page.getByTestId('login-email-input')).toBeVisible();
        expect((await swState(page)).controlled, 'reload 後頁面由 SW 接管').toBe(true);
        return { url: response!.url(), html: await response!.text() };
      }
    );

    await apiStep(
      'SW 回的 index.html 引用的 JS / CSS 都能從伺服器直接取得（不經 SW）',
      async () => {
        const origin = new URL(navigation.url).origin;
        const assets = referencedAssets(navigation.html, origin);
        expect(
          assets.some((u) => u.endsWith('.js')),
          'index.html 至少引用一個 JS'
        ).toBe(true);
        const results = [];
        for (const url of assets) {
          // page.request 不經過頁面的 SW，等於「伺服器上現在還有沒有這個檔案」
          const res = await page.request.get(url);
          const contentType = res.headers()['content-type'] ?? '';
          const head = (await res.text()).slice(0, 64).trimStart().toLowerCase();
          results.push({ url: new URL(url).pathname, status: res.status(), contentType });
          expect(res.status(), `${url} 狀態碼`).toBe(200);
          // 缺檔時 Firebase SPA rewrite 會回 200 + index.html：必須是 JS / CSS，不能是 HTML
          expect(contentType, `${url} Content-Type`).toMatch(/javascript|css/);
          expect(
            head.startsWith('<!doctype') || head.startsWith('<html'),
            `${url} 不應是 HTML`
          ).toBe(false);
        }
        return { navigationUrl: navigation.url, assets: results };
      }
    );

    await apiStep('頁面發出的 /api 請求不經 SW', async () => {
      const apiResponse = page.waitForResponse(
        (r) => new URL(r.url()).pathname === '/api/sw-probe'
      );
      // 不存在的公開路徑：只看「是否經過 SW」，不依賴任何業務資料；狀態碼由後端決定（404/401 皆可）
      const status = await page.evaluate(() =>
        fetch('/api/sw-probe', { cache: 'no-store' }).then((r) => r.status)
      );
      const res = await apiResponse;
      expect(res.fromServiceWorker(), '/api 不應由 SW 回應').toBe(false);
      expect(res.headers()['content-type'] ?? '', '/api 不應拿到 index.html').not.toMatch(
        /text\/html/
      );
      return { status, fromServiceWorker: res.fromServiceWorker() };
    });
  });
});
