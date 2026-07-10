import { test, expect } from '@playwright/test';

test.describe('Sitter Public Profile & Forbidden Keywords Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('should manage public profile and enforce forbidden keywords and gating', async ({ page }) => {
    // 這支測試步驟特別多 (4次保存/多次角色切換/KYC/關鍵字管理，每個都是跨區真實API呼叫)，
    // 光是成功的一次執行就逼近預設30s上限，拉寬到60s避免正常的網路延遲被誤判成失敗
    test.setTimeout(60000);

    // 1. 保母登入（切換為保母）並填寫公開檔案
    await page.getByRole('button', { name: '切換為保母' }).click();
    await expect(page.locator('text=當前角色: 貓咪保母')).toBeVisible();
    await page.getByTestId('btn-go-sitter-profile-settings').click();

    await page.getByTestId('sitter-profile-input-display-name').fill('愛貓阿香保母');
    await page.getByTestId('sitter-profile-input-bio').fill('提供十年專業貓咪照顧，細心又貼心。');

    // 確保公開檔案是開啟狀態 (避免被上次測試結果污染)
    const toggleCheckbox = page.getByTestId('sitter-profile-toggle-visible');
    if (!(await toggleCheckbox.isChecked())) {
      await page.locator('label:has([data-testid="sitter-profile-toggle-visible"]) span').first().click();
    }

    // 新增標籤
    await page.getByTestId('sitter-profile-input-tag').fill('有耐性');
    await page.getByTestId('sitter-profile-btn-add-tag').click();

    // 新增區域
    await page.getByTestId('sitter-profile-input-city').fill('台北市');
    await page.getByTestId('sitter-profile-input-district').fill('大安區');
    await page.getByTestId('sitter-profile-btn-add-area').click();

    // 儲存公開檔案
    await page.getByTestId('sitter-profile-btn-save').click();

    // 驗證儲存成功提示
    const messageLocator = page.getByTestId('sitter-profile-message');
    await expect(messageLocator).toHaveText(/公開檔案儲存成功/);

    // 2. 切換為管理員，設定敏感詞
    await page.click('text=返回 Demo 首頁');
    await page.getByRole('button', { name: '切換為管理員' }).click();
    await expect(page.locator('text=當前角色: 系統管理員')).toBeVisible();
    await page.getByTestId('btn-go-admin-keywords').click();

    const randKeyword = `買賣${Math.floor(Math.random() * 10000)}`;
    await page.getByTestId('admin-keyword-input-add').fill(randKeyword);
    await page.getByTestId('admin-keyword-btn-add').click();

    const adminMessage = page.getByTestId('admin-keyword-message');
    await expect(adminMessage).toHaveText(new RegExp(`成功新增敏感詞: "${randKeyword}"`));

    // 3. 切換回保母，嘗試使用敏感詞，預期被阻擋
    await page.click('text=返回 Demo 首頁');
    await page.getByRole('button', { name: '切換為保母' }).click();
    await expect(page.locator('text=當前角色: 貓咪保母')).toBeVisible();
    await page.getByTestId('btn-go-sitter-profile-settings').click();

    await page.getByTestId('sitter-profile-input-display-name').fill(`貓咪${randKeyword}阿香`);
    await page.getByTestId('sitter-profile-btn-save').click();

    await expect(messageLocator).toHaveText(/內容包含敏感詞彙/);

    // 恢復正常暱稱並儲存
    await page.getByTestId('sitter-profile-input-display-name').fill('愛貓阿香保母');
    await page.getByTestId('sitter-profile-btn-save').click();
    await expect(messageLocator).toHaveText(/公開檔案儲存成功/);

    // 4. 前往飼主預約頁面，驗證資料展示
    await page.click('text=返回 Demo 首頁');
    await page.getByRole('button', { name: '切換為飼主' }).click();
    await expect(page.locator('text=當前角色: 愛貓飼主')).toBeVisible();
    await page.getByRole('button', { name: '進入預約精靈 (飼主端)' }).click();

    const sitterName = page.getByTestId('client-booking-sitter-name');
    const sitterBio = page.getByTestId('client-booking-sitter-bio');
    const sitterTags = page.getByTestId('client-booking-sitter-tags');
    const sitterAreas = page.getByTestId('client-booking-sitter-areas');

    await expect(sitterName).toHaveText('愛貓阿香保母');
    await expect(sitterBio).toHaveText('提供十年專業貓咪照顧，細心又貼心。');
    await expect(sitterTags).toContainText('有耐性');
    await expect(sitterAreas).toContainText('台北市 大安區');

    // 5. 測試 Gating 隱私卡控
    // 返回首頁，保母切換為關閉公開 (isVisible = false)
    await page.click('text=返回 Demo 首頁');
    await page.getByRole('button', { name: '切換為保母' }).click();
    await expect(page.locator('text=當前角色: 貓咪保母')).toBeVisible();
    await page.getByTestId('btn-go-sitter-profile-settings').click();

    // 確保公開檔案是關閉狀態
    const toggleCheckbox5 = page.getByTestId('sitter-profile-toggle-visible');
    if (await toggleCheckbox5.isChecked()) {
      await page.locator('label:has([data-testid="sitter-profile-toggle-visible"]) span').first().click();
    }
    await page.getByTestId('sitter-profile-btn-save').click();
    await expect(messageLocator).toHaveText(/公開檔案儲存成功/);

    // 再切回飼主，進入預約，應該 gated
    await page.click('text=返回 Demo 首頁');
    await page.getByRole('button', { name: '切換為飼主' }).click();
    await expect(page.locator('text=當前角色: 愛貓飼主')).toBeVisible();
    await page.getByRole('button', { name: '進入預約精靈 (飼主端)' }).click();

    // 名稱應變為保母休息中，且有警示 banner
    await expect(sitterName).toHaveText('保母休息中');
    await expect(page.locator('text=此保母目前暫停服務或處於休息狀態，暫時無法接受預約。')).toBeVisible();
    
    // 下一步按鈕不應顯示
    const nextBtn = page.getByTestId('client-booking-btn-step1-next');
    await expect(nextBtn).not.toBeVisible();

    // 6. 還原公開檔案為開啟狀態，避免污染其他測試共用的種子資料
    // （資料庫在測試之間是持久化的，這裡若不還原，下一輪測試會誤判此保母為 gated）
    await page.click('text=返回 Demo 首頁');
    await page.getByRole('button', { name: '切換為保母' }).click();
    await expect(page.locator('text=當前角色: 貓咪保母')).toBeVisible();
    await page.getByTestId('btn-go-sitter-profile-settings').click();

    const toggleCheckbox6 = page.getByTestId('sitter-profile-toggle-visible');
    if (!(await toggleCheckbox6.isChecked())) {
      await page.locator('label:has([data-testid="sitter-profile-toggle-visible"]) span').first().click();
    }
    await page.getByTestId('sitter-profile-btn-save').click();
    await expect(messageLocator).toHaveText(/公開檔案儲存成功/);
  });
});
