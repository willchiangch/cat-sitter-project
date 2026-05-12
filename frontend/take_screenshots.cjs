const { chromium } = require('@playwright/test');
const path = require('path');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.setViewportSize({ width: 375, height: 812 }); // iPhone 尺寸

  const artifactDir = '/Users/will_chiang/.gemini/antigravity/brain/a7821830-bf61-4ea3-a292-0a7785fa7610/';

  console.log('1. Capturing Sitter Home...');
  await page.goto('http://localhost:5173/');
  await page.screenshot({ path: path.join(artifactDir, '01_home_sitter.png') });

  console.log('2. Capturing Client Home...');
  await page.getByTestId('btn-role-toggle').click();
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(artifactDir, '02_home_client.png') });

  console.log('3. Capturing Sitter Orders...');
  await page.getByTestId('btn-role-toggle').click(); // Switch back to sitter
  await page.getByRole('button', { name: '進入訂單管理' }).click();
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(artifactDir, '03_sitter_orders.png') });

  console.log('4. Capturing Order Evaluation...');
  await page.goto('http://localhost:5173/');
  await page.getByRole('button', { name: '進入報價評估' }).click();
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(artifactDir, '04_order_eval_initial.png') });

  console.log('5. Capturing Final Evaluation Result...');
  await page.getByTestId('sitter-order-eval-input-add-fee').fill('100');
  await page.getByTestId('sitter-order-eval-input-discount').fill('200');
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(artifactDir, '05_order_eval_final.png') });

  await browser.close();
  console.log('All screenshots captured successfully.');
})();
