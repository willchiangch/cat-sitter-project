import { test, expect } from '@playwright/test';

test.describe('PRD-004 事前問卷設定', () => {
  test.setTimeout(60000);

  let questions: any[] = [];

  test.beforeEach(async ({ page }) => {
    questions = [];

    await page.route('**/api/sitter/questions/sort', async (route) => {
      const body = route.request().postDataJSON() as { questionIds: string[] };
      questions = body.questionIds.map((id) => questions.find((q) => q.id === id));
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'OK', data: null }) });
    });

    await page.route('**/api/sitter/questions/*/active', async (route) => {
      const url = route.request().url();
      const id = url.split('/').slice(-2)[0];
      const body = route.request().postDataJSON() as { active: boolean };
      const q = questions.find((q) => q.id === id);
      if (q) q.isActive = body.active;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'OK', data: q }) });
    });

    await page.route('**/api/sitter/questions/*', async (route) => {
      if (route.request().method() === 'DELETE') {
        const id = route.request().url().split('/').pop();
        questions = questions.filter((q) => q.id !== id);
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'OK', data: null }) });
        return;
      }
      return route.fallback();
    });

    await page.route('**/api/sitter/questions', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(questions) });
        return;
      }
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON();
        const newQuestion = { ...body, id: `q-${questions.length + 1}`, sortOrder: questions.length, isActive: true, version: 0 };
        questions.push(newQuestion);
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'OK', data: newQuestion }) });
        return;
      }
      return route.fallback();
    });

    await page.goto('/demo');
    await page.getByRole('button', { name: '切換為保母' }).click();
    await page.getByRole('button', { name: '進入事前問卷設定 (保母端)' }).click();
  });

  test('可新增單選題，未填選項時應擋下', async ({ page }) => {
    await page.getByTestId('question-add-btn').click();
    await page.getByTestId('question-text-input').fill('毛孩是否會攻擊人？');
    await page.getByTestId('question-answer-type-select').selectOption('RADIO');

    await page.getByTestId('question-save-btn').click();
    await expect(page.getByTestId('question-form-error')).toContainText('必須至少設定一個選項');

    await page.getByTestId('question-options-textarea').fill('會\n不會');
    await page.getByTestId('question-save-btn').click();

    await expect(page.getByTestId('question-list')).toContainText('毛孩是否會攻擊人？');
    await expect(page.getByTestId('question-list')).toContainText('選項：會、不會');
  });

  test('可停用題目、調整排序、刪除題目', async ({ page }) => {
    await page.getByTestId('question-add-btn').click();
    await page.getByTestId('question-text-input').fill('第一題');
    await page.getByTestId('question-save-btn').click();
    await expect(page.getByTestId('question-list')).toContainText('第一題');

    await page.getByTestId('question-add-btn').click();
    await page.getByTestId('question-text-input').fill('第二題');
    await page.getByTestId('question-save-btn').click();

    const rows = page.locator('[data-testid^="question-row-"]');
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText('第一題');

    // 排序：把第二題往上移
    await page.getByTestId('question-move-up-q-2').click();
    await expect(rows.first()).toContainText('第二題');

    // 停用第二題
    await page.getByTestId('question-toggle-active-q-2').click();
    await expect(rows.first()).toContainText('已停用');

    // 刪除第一題
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByTestId('question-delete-q-1').click();
    await expect(rows).toHaveCount(1);
  });
});
