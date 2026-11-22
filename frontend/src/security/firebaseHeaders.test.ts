// @vitest-environment node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * SD-FRONTEND-SPEC §2.6 驗收 (1)：解析根目錄 firebase.json 的 hosting.headers。
 * CSP 目前刻意以 `Content-Security-Policy-Report-Only` 部署（§2.6 上線方式 / NFR-003 CQ-22）；
 * Report-Only 期結束改為強制時，本測試的 CSP_KEY 須同步改成 `Content-Security-Policy`。
 */
const CSP_KEY = 'Content-Security-Policy-Report-Only';

interface HeaderEntry {
  key: string;
  value: string;
}
interface FirebaseJson {
  hosting: {
    headers?: { source?: string; regex?: string; headers: HeaderEntry[] }[];
  };
}

const firebaseJsonPath = fileURLToPath(new URL('../../../firebase.json', import.meta.url));
const config = JSON.parse(readFileSync(firebaseJsonPath, 'utf8')) as FirebaseJson;

const allPathsRule = (config.hosting.headers ?? []).find((h) => h.source === '**');
const headers = new Map((allPathsRule?.headers ?? []).map((h) => [h.key, h.value]));

/** §2.6 表格的 CSP，逐 directive 比對 */
const EXPECTED_CSP: Record<string, string[]> = {
  'default-src': ["'self'"],
  'script-src': ["'self'", 'https://accounts.google.com/gsi/client'],
  'style-src': ["'self'", "'unsafe-inline'", 'https://accounts.google.com/gsi/style'],
  'font-src': ["'self'"],
  'img-src': [
    "'self'",
    'data:',
    'blob:',
    'https://storage.googleapis.com',
    'https://*.googleusercontent.com'
  ],
  'media-src': ["'self'", 'blob:', 'https://storage.googleapis.com'],
  'connect-src': ["'self'", 'https://storage.googleapis.com', 'https://accounts.google.com/gsi/'],
  'frame-src': ['https://accounts.google.com/gsi/'],
  'frame-ancestors': ["'none'"],
  'object-src': ["'none'"],
  'base-uri': ["'self'"],
  'form-action': ["'self'"],
  'worker-src': ["'self'"],
  'manifest-src': ["'self'"]
};

const parseCsp = (value: string): Record<string, string[]> =>
  Object.fromEntries(
    value
      .split(';')
      .map((d) => d.trim())
      .filter(Boolean)
      .map((d) => {
        const [name, ...sources] = d.split(/\s+/);
        return [name.toLowerCase(), sources];
      })
  );

describe('firebase.json hosting.headers（SD-FRONTEND-SPEC §2.6）', () => {
  it('有套用到所有路徑（source "**"）的標頭規則', () => {
    expect(allPathsRule).toBeDefined();
  });

  it('CSP 以 Report-Only 下發（CQ-22），且不同時存在強制版', () => {
    expect(headers.has(CSP_KEY)).toBe(true);
    expect(headers.has('Content-Security-Policy')).toBe(false);
  });

  it('CSP directives 與 §2.6 表格完全一致', () => {
    const csp = parseCsp(headers.get(CSP_KEY) ?? '');
    expect(Object.keys(csp).sort()).toEqual(Object.keys(EXPECTED_CSP).sort());
    for (const [name, sources] of Object.entries(EXPECTED_CSP)) {
      expect(csp[name], name).toEqual(sources);
    }
  });

  it("script-src 不含 'unsafe-inline' / 'unsafe-eval'；必備 frame-ancestors / object-src / base-uri", () => {
    const csp = parseCsp(headers.get(CSP_KEY) ?? '');
    expect(csp['script-src']).not.toContain("'unsafe-inline'");
    expect(csp['script-src']).not.toContain("'unsafe-eval'");
    expect(csp['frame-ancestors']).toEqual(["'none'"]);
    expect(csp['object-src']).toEqual(["'none'"]);
    expect(csp['base-uri']).toEqual(["'self'"]);
  });

  it.each([
    ['Strict-Transport-Security', 'max-age=31536000; includeSubDomains'],
    ['X-Content-Type-Options', 'nosniff'],
    ['Referrer-Policy', 'strict-origin-when-cross-origin'],
    ['X-Frame-Options', 'DENY'],
    ['Permissions-Policy', 'camera=(), microphone=(), geolocation=()']
  ])('%s = %s', (key, value) => {
    expect(headers.get(key)).toBe(value);
  });
});
