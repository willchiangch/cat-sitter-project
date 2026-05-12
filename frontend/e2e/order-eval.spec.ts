import { test, expect } from '@playwright/test';

test.describe('Order Evaluation Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('should toggle role and change theme color', async ({ page }) => {
    const title = page.locator('h1');
    const toggleBtn = page.getByTestId('btn-role-toggle');

    // Default is Sitter (Stitch Amber)
    await expect(title).toHaveCSS('color', 'rgb(118, 86, 0)');

    await toggleBtn.click();
    // Switched to Client (Stitch Blue)
    await expect(title).toHaveCSS('color', 'rgb(0, 94, 159)');
  });

  test('should navigate to evaluation and check total calculation', async ({ page }) => {
    await page.getByRole('button', { name: '進入報價評估' }).click();
    
    const addFeeInput = page.getByTestId('sitter-order-eval-input-add-fee');
    const discountInput = page.getByTestId('sitter-order-eval-input-discount');
    const totalText = page.locator('text=報價總計').locator('..').locator('span').last();

    await expect(totalText).toHaveText('$ 2,400');

    await addFeeInput.fill('100');
    await expect(totalText).toHaveText('$ 2,500');

    await discountInput.fill('200');
    await expect(totalText).toHaveText('$ 2,300');
  });
});
