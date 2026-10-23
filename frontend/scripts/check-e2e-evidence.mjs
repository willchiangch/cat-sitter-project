// E2E 證據規範守門：journey 測試禁止繞過 e2e/support/evidence.ts 自己截圖/吞對話框。
// 用法：node scripts/check-e2e-evidence.mjs（npm run e2e:lint）
import fs from 'fs';
import path from 'path';

const ROOT = path.resolve(import.meta.dirname, '../e2e/journeys');
const RULES = [
  { re: /testInfo\.attach\(/, msg: '請改用 snap()/step()/apiStep()，不要直接 testInfo.attach' },
  { re: /\.screenshot\(/, msg: '請改用 snap()，它會先等畫面穩定、自動編號' },
  { re: /\btest\.step\(/, msg: 'UI 步驟用 step(label, page, fn)，API 步驟用 apiStep(label, fn)' },
  { re: /\.on\(\s*['"]dialog['"]/, msg: '請改用 trackDialogs(page)，否則 alert 結果不會進報告' }
];

let failed = 0;
for (const file of fs.readdirSync(ROOT).filter((f) => f.endsWith('.spec.ts'))) {
  const lines = fs.readFileSync(path.join(ROOT, file), 'utf8').split('\n');
  lines.forEach((line, i) => {
    for (const { re, msg } of RULES) {
      if (re.test(line)) {
        console.error(`e2e/journeys/${file}:${i + 1}  ${msg}\n    ${line.trim()}`);
        failed++;
      }
    }
  });
  // 每個 click 之後、下一個 snap/step 結束前，必須至少有一個 expect/await*（等待結果），否則截到的是點擊前的畫面
  lines.forEach((line, i) => {
    if (!/\.click\(/.test(line)) return;
    const next = lines.slice(i + 1).find((l) => l.trim() && !l.trim().startsWith('//'));
    if (next && /\bsnap\(/.test(next)) {
      console.error(
        `e2e/journeys/${file}:${i + 2}  click 後直接 snap，請先 expect 操作結果\n    ${next.trim()}`
      );
      failed++;
    }
  });
}
if (failed) {
  console.error(`\n${failed} 個 E2E 證據規範違規`);
  process.exit(1);
}
console.log('E2E 證據規範檢查通過');
