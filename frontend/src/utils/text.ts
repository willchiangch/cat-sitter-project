/**
 * 文字欄位的空白與字數（SD-GLOBAL-SPEC §2.7）：「空白」= Java `Character.isWhitespace`（含 U+3000、Tab、換行）
 * 再加 U+00A0；字數以 strip 後的 Unicode code point 計（與後端一致）。
 * 計數一律 `[...value].length`；不得用 `<textarea maxLength>`（以 UTF-16 code unit 計，emoji 會被提早截斷）。
 */
const isBlankChar = (ch: string): boolean => {
  const c = ch.codePointAt(0) ?? 0;
  if (c === 0x00a0) return true;
  // Character.isWhitespace：Unicode 空白分隔符（Zs/Zl/Zp）但不含不換行空白 U+2007、U+202F，另加 \t\n\v\f\r 與 U+001C–U+001F
  if (c >= 0x09 && c <= 0x0d) return true;
  if (c >= 0x1c && c <= 0x20) return true;
  if (c === 0x2007 || c === 0x202f) return false;
  return /[\p{Zs}\p{Zl}\p{Zp}]/u.test(ch);
};

export const stripText = (value: string): string => {
  const chars = [...value];
  let start = 0;
  let end = chars.length;
  while (start < end && isBlankChar(chars[start])) start += 1;
  while (end > start && isBlankChar(chars[end - 1])) end -= 1;
  return chars.slice(start, end).join('');
};

export const codePointLength = (value: string): number => [...value].length;

/** onChange 截斷：超過 max 個 code point 的部分丟棄 */
export const truncateCodePoints = (value: string, max: number): string => {
  const chars = [...value];
  return chars.length > max ? chars.slice(0, max).join('') : value;
};

/** 必填欄位：strip 後 1–max code point */
export const isValidRequiredText = (value: string, max: number): boolean => {
  const len = codePointLength(stripText(value));
  return len >= 1 && len <= max;
};
