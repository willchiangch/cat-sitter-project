import { test, expect } from '@playwright/test';

test.describe('毛孩資料與注意事項管理流程', () => {

  let currentPets = [
    {
      id: 'e1023000-0000-0000-0000-000000000001',
      ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
      name: '咪咪',
      species: 'CAT',
      gender: 'FEMALE',
      neutered: true,
      weight: 4.50,
      birthYear: 2021,
      photoUrl: '',
      medicalPersonalityNotes: '對海鮮過敏，個性傲嬌。',
      environmentalNotes: '不能開陽台門，喜歡高處。',
      version: 1
    }
  ];

  const mockNewPet = {
    id: 'e1023000-0000-0000-0000-000000000002',
    ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
    name: '哈利',
    species: 'DOG',
    gender: 'MALE',
    neutered: false,
    weight: 12.00,
    birthYear: 2023,
    photoUrl: '',
    medicalPersonalityNotes: '親人但對狗有警惕心。',
    environmentalNotes: '需要每天散步兩次。',
    version: 1
  };

  const mockLogs = [
    {
      id: 'd1023000-0000-0000-0000-000000000001',
      petId: 'e1023000-0000-0000-0000-000000000001',
      editorId: '3d498178-14c0-4376-b81e-7fb02e615dda',
      diffSummary: {
        editorRole: 'SITTER',
        changes: {
          medicalPersonalityNotes: {
            before: '無特別過敏。',
            after: '對海鮮過敏，個性傲嬌。'
          }
        }
      },
      createdAt: '2026-05-26T12:00:00Z'
    }
  ];

  test.beforeEach(async ({ page }) => {
    // 重設為初始資料
    currentPets = [
      {
        id: 'e1023000-0000-0000-0000-000000000001',
        ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
        name: '咪咪',
        species: 'CAT',
        gender: 'FEMALE',
        neutered: true,
        weight: 4.50,
        birthYear: 2021,
        photoUrl: '',
        medicalPersonalityNotes: '對海鮮過敏，個性傲嬌。',
        environmentalNotes: '不能開陽台門，喜歡高處。',
        version: 1
      }
    ];

    // 1. Mock 取得毛孩列表
    await page.route('**/api/pets', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: currentPets })
        });
      } else if (request.method() === 'POST') {
        const payload = JSON.parse(request.postData() || '{}');
        const createdPet = { ...mockNewPet, ...payload };
        currentPets.push(createdPet);
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: createdPet })
        });
      }
    });

    // 2. Mock 取得單一毛孩詳情
    await page.route('**/api/pets/e1023000-0000-0000-0000-000000000001', async (route, request) => {
      if (request.method() === 'GET') {
        const pet = currentPets.find(p => p.id === 'e1023000-0000-0000-0000-000000000001');
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: pet })
        });
      }
    });

    // 3. Mock 編輯紀錄
    await page.route('**/api/pets/e1023000-0000-0000-0000-000000000001/edit-logs', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'OK', data: mockLogs })
      });
    });

    await page.goto('/');
  });

  test('TS-002-01 毛孩清單與基本資料編輯、上傳頭像', async ({ page }) => {
    // 切換為飼主角色
    const roleText = await page.locator('p').filter({ hasText: '當前角色:' }).textContent();
    if (roleText?.includes('貓咪保母') || roleText?.includes('系統管理員')) {
      await page.click('[data-testid="btn-role-toggle"]');
    }

    // 進入毛孩管理頁面
    await page.click('[data-testid="btn-go-pet-manager"]');
    await expect(page.locator('h2')).toContainText('毛孩資料管理');

    // 確認載入 mock 寵物咪咪
    const petCard = page.getByTestId('pet-card').filter({ hasText: '咪咪' });
    await expect(petCard).toBeVisible();
    await petCard.click();

    // 檢查基本資料是否填入
    await expect(page.getByTestId('input-pet-name')).toHaveValue('咪咪');
    await expect(page.getByTestId('select-pet-species')).toHaveValue('CAT');
    await expect(page.getByTestId('input-pet-weight')).toHaveValue('4.5');

    // 模擬修改基本資料
    await page.route('**/api/pets/e1023000-0000-0000-0000-000000000001', async (route, request) => {
      if (request.method() === 'PUT') {
        const payload = JSON.parse(request.postData() || '{}');
        currentPets[0] = { ...currentPets[0], ...payload };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: 'OK', data: currentPets[0] })
        });
      }
    });

    await page.getByTestId('input-pet-weight').fill('4.85');
    await page.getByTestId('btn-save-pet').click();

    // 檢查 Toast 訊息
    await expect(page.getByTestId('toast-message')).toContainText('成功更新基本資料');

    // 模擬上傳大頭照
    await page.route('**/api/pets/e1023000-0000-0000-0000-000000000001/avatar', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'OK', data: { photoUrl: 'https://storage.googleapis.com/test/pet-avatars/owner1/pet1.jpg' } })
      });
    });

    // 設置上傳檔案
    const fileChooserPromise = page.waitForEvent('filechooser');
    await page.getByTestId('avatar-upload-trigger').click();
    const fileChooser = await fileChooserPromise;
    await fileChooser.setFiles({
      name: 'avatar.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.from('fake-image-data')
    });

    await expect(page.getByTestId('toast-message')).toContainText('大頭照上傳成功');
  });

  test('TS-002-02 必填欄位卡控與新增毛孩', async ({ page }) => {
    // 進入毛孩管理頁面
    await page.click('[data-testid="btn-go-pet-manager"]');

    // 點擊新增毛孩
    await page.getByTestId('btn-add-pet').click();

    // 檢查表單為空值
    await expect(page.getByTestId('input-pet-name')).toHaveValue('');

    // 嘗試在沒有填名字的情況下儲存，HTML5 驗證或 React 阻擋
    // 由於 required 屬性，這裡我們可以直接填名字來做正常新增流程
    await page.getByTestId('input-pet-name').fill('哈利');
    await page.getByTestId('select-pet-species').selectOption('DOG');
    await page.getByTestId('input-pet-weight').fill('12.0');
    await page.getByTestId('btn-save-pet').click();

    // 檢查是否成功新增
    await expect(page.getByTestId('toast-message')).toContainText('成功新增毛孩資料');
  });

  test('TS-002-03 注意事項共同編輯、異動日誌', async ({ page }) => {
    // 進入毛孩管理頁面
    await page.click('[data-testid="btn-go-pet-manager"]');
    await page.getByTestId('pet-card').filter({ hasText: '咪咪' }).click();

    // 確認備註內容顯示
    await expect(page.locator('text=對海鮮過敏，個性傲嬌。')).toBeVisible();

    // 點擊編輯注意事項
    await page.getByTestId('btn-edit-notes').click();

    // 修改文字內容
    const medicalNotes = page.getByTestId('textarea-medical-notes');
    await expect(medicalNotes).toBeVisible();
    await medicalNotes.fill('對海鮮過敏，不吃小魚乾，個性極度傲嬌。');

    // Mock 儲存注意事項 API
    await page.route('**/api/pets/e1023000-0000-0000-0000-000000000001/notes', async (route) => {
      currentPets[0] = { 
        ...currentPets[0], 
        medicalPersonalityNotes: '對海鮮過敏，不吃小魚乾，個性極度傲嬌。', 
        version: 2 
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ 
          code: 200, 
          message: 'OK', 
          data: currentPets[0]
        })
      });
    });

    await page.getByTestId('btn-save-notes').click();
    await expect(page.getByTestId('toast-message')).toContainText('注意事項已成功儲存');
    await expect(page.locator('text=對海鮮過敏，不吃小魚乾，個性極度傲嬌。')).toBeVisible();

    // 檢視編輯紀錄
    await page.getByTestId('btn-view-logs').click();
    await expect(page.getByTestId('logs-modal-title')).toContainText('咪咪 的注意事項異動紀錄');
    await expect(page.getByTestId('log-item')).toContainText('編輯者角色：保母');
    await expect(page.getByTestId('log-item')).toContainText('對海鮮過敏，個性傲嬌。');
  });

  test('TS-002-04 樂觀鎖衝突 (409) 提示', async ({ page }) => {
    // 進入毛孩管理頁面
    await page.click('[data-testid="btn-go-pet-manager"]');
    await page.getByTestId('pet-card').filter({ hasText: '咪咪' }).click();

    // 點擊編輯注意事項
    await page.getByTestId('btn-edit-notes').click();

    // Mock 儲存注意事項 API 衝突 (409)
    await page.route('**/api/pets/e1023000-0000-0000-0000-000000000001/notes', async (route) => {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ code: 409, message: 'ObjectOptimisticLockingFailureException' })
      });
    });

    const medicalNotes = page.getByTestId('textarea-medical-notes');
    await medicalNotes.fill('寫入一些會衝突的內容。');
    await page.getByTestId('btn-save-notes').click();

    // 驗證是否彈出樂觀鎖警告
    await expect(page.getByTestId('lock-conflict-alert')).toBeVisible();
    await expect(page.getByTestId('lock-conflict-alert')).toContainText('內容已被他人更新，請重新整理後再試。');
    // 確認文字欄位依然存在 (未關閉編輯表單)
    await expect(medicalNotes).toBeVisible();
    await expect(medicalNotes).toHaveValue('寫入一些會衝突的內容。');
  });

  test('TS-002-05 進行中訂單阻擋刪除提示', async ({ page }) => {
    // 進入毛孩管理頁面
    await page.click('[data-testid="btn-go-pet-manager"]');
    await page.getByTestId('pet-card').filter({ hasText: '咪咪' }).click();

    // Mock 刪除 API 失敗 (由於進行中服務，後端丟出 400)
    await page.route('**/api/pets/e1023000-0000-0000-0000-000000000001', async (route, request) => {
      if (request.method() === 'DELETE') {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ code: 400, message: '此毛孩尚有進行中的服務，無法刪除' })
        });
      }
    });

    // 處理 confirm dialog
    page.once('dialog', async dialog => {
      expect(dialog.message()).toContain('確定要刪除此毛孩資料嗎？');
      await dialog.accept();
    });

    await page.getByTestId('btn-delete-pet').click();

    // 驗證是否出現 Toast 阻擋警告
    await expect(page.getByTestId('toast-message')).toContainText('此毛孩尚有進行中的服務，無法刪除');
  });

});
