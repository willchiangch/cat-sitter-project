import fs from 'fs';
import path from 'path';
import { test, expect } from '@playwright/test';
import { step } from './support/evidence';

/**
 * fix-batch14 N4（驗收 20261026-1517）：step() 過去丟掉 body 的回傳值，K9 / K10 量到的對比度
 * （`return { ratio }`）沒有進報告。現在可序列化的回傳值以 JSON 附在截圖之後，套用 apiStep 同一套遮蔽規則；
 * Locator 之類的物件不附檔，manifest 記 note.unserializable。
 */
interface ManifestLine {
  idx: string;
  kind: string;
  label: string;
  file: string;
  note?: { unserializable?: string };
}

function manifest(): ManifestLine[] {
  const file = path.join(test.info().outputPath('evidence'), 'manifest.jsonl');
  return fs
    .readFileSync(file, 'utf8')
    .trim()
    .split('\n')
    .map((l) => JSON.parse(l) as ManifestLine);
}

test.describe('evidence.ts step() 回傳值證據 (N4)', () => {
  test('可序列化回傳值寫成 JSON（敏感 key 遮蔽、null 保留），undefined 不附檔，Locator 只記原因', async ({
    page
  }) => {
    await page.setContent('<main><p data-testid="probe">量測目標</p></main>');

    const measured = await step('量測並回傳', page, async () => {
      await expect(page.getByTestId('probe')).toHaveText('量測目標');
      return {
        ratio: 6.28,
        color: 'rgb(176, 37, 0)',
        accessToken: 'secret-value',
        refreshToken: null
      };
    });
    // 回傳值照常交還呼叫方（不是遮蔽後的版本）
    expect(measured.accessToken).toBe('secret-value');

    await step('沒有回傳值', page, async () => {
      await expect(page.getByTestId('probe')).toBeVisible();
    });

    await step('回傳 Locator', page, async () => {
      const probe = page.getByTestId('probe');
      await expect(probe).toBeVisible();
      return probe;
    });

    const lines = manifest();
    expect(lines.map((l) => [l.kind, l.label])).toEqual([
      ['screenshot', '量測並回傳'],
      ['data', '量測並回傳-回傳值'],
      ['screenshot', '沒有回傳值'],
      ['screenshot', '回傳 Locator'],
      ['data', '回傳 Locator-回傳值']
    ]);
    const data = JSON.parse(fs.readFileSync(lines[1].file, 'utf8'));
    expect(data).toEqual({
      ratio: 6.28,
      color: 'rgb(176, 37, 0)',
      accessToken: '***',
      refreshToken: null
    });
    expect(lines[1].file.endsWith('.json')).toBe(true);
    expect(lines[4].file.endsWith('.txt')).toBe(true);
    expect(lines[4].note?.unserializable).toMatch(/^回傳值含非資料物件 \w+/);
  });
});
