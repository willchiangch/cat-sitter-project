import { test, expect } from '@playwright/test';
import { step } from './support/evidence';
import { assertNoUnmockedApi, mockDemoSession, serveFixtureImage } from './support/mockSession';

// N9：漏 mock 的 /api 請求會被 catch-all 攔下回 501，這裡讓它變紅而不是靜默打到正式站
test.afterEach(({ page }) => assertNoUnmockedApi(page));

/**
 * Backlog 第 3 批 U8（驗收 N3）：保母核對面板的「駁回憑證」是棕字配金底（.btn-primary 漸層沒被蓋掉），
 * 對比度遠低於 WCAG AA 一般文字 4.5:1。以實際渲染的前景/背景色計算對比度。
 */
const orderId = 'd1000000-0000-0000-0000-0000000000a8';
const PROOF_URL = 'https://storage.example.test/payment-proofs/p.jpg?X-Goog-Signature=mock';

const order = {
  id: orderId,
  ownerId: 'o-1',
  ownerName: '陳先生',
  sitterId: 's-1',
  sitterName: '測試保母',
  status: 'PAID',
  totalAmount: 1200,
  amountDue: 1200,
  hasPaymentProof: true,
  paymentProofLastFive: '12345',
  scheduledDatesLabel: '2026-10-24 (共 1 天)'
};

test('U8：「駁回憑證」按鈕文字對背景對比度 ≥ 4.5:1，且沒有漸層底', async ({ page }) => {
  await mockDemoSession(page, 'sitter');
  await page.route('**/api/orders/sitter', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([order]) })
  );
  await page.route(`**/api/orders/${orderId}/payment-proof/signed-url`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ signedUrl: PROOF_URL, expiresInSeconds: 600 })
    })
  );
  await serveFixtureImage(page, PROOF_URL, 'kyc-selfie.jpg');

  await step('保母訂單「進行中」分頁：核對面板的駁回憑證按鈕', page, async () => {
    await page.goto('/sitter/orders');
    await page.getByTestId('sitter-orders-tab-ongoing').click();
    const btn = page.getByTestId('btn-reject-payment');
    await expect(btn).toBeVisible();
    await expect(btn).toHaveText('駁回憑證');

    const result = await btn.evaluate((el) => {
      const parse = (c: string) => (c.match(/[\d.]+/g) ?? []).map(Number);
      const lum = ([r, g, b]: number[]) => {
        const ch = (v: number) => {
          const s = v / 255;
          return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        };
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
      };
      const cs = getComputedStyle(el);
      const fg = parse(cs.color);
      const bg = parse(cs.backgroundColor);
      const [hi, lo] = [lum(fg), lum(bg)].sort((a, b) => b - a);
      return {
        backgroundImage: cs.backgroundImage,
        bgAlpha: bg.length === 4 ? bg[3] : 1,
        ratio: (hi + 0.05) / (lo + 0.05)
      };
    });
    // 背景要是實色：有漸層時 backgroundColor 不代表實際看到的底色，算出來的對比度沒有意義
    expect(result.backgroundImage).toBe('none');
    expect(result.bgAlpha).toBe(1);
    expect(result.ratio).toBeGreaterThanOrEqual(4.5);
  });
});
