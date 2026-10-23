/**
 * Demo 種子帳號自動登入的適用範圍 (SD-001 §Demo 角色切換)。
 *
 * 種子帳號（sitter@test.com / owner@test.com / admin@test.com）自動登入是給 Demo 首頁「切換角色」
 * 展示用的，只能在使用者「明確進入 Demo」時發生：
 * - 公開頁（登入/註冊/忘記密碼/重設密碼、保母公開頁）一律不登入：這些是真實匿名訪客的入口。
 *   過去只排除前四個，匿名開保母公開頁會在背景以 sitter@test.com 登入，公開頁 API 也帶著種子保母的
 *   token 打出去，看到的不是匿名視角。
 * - 已透過正式登入頁登入 (authMode=manual) 不登入，避免蓋掉真實 session。
 * - 已在 Demo 模式中 (authMode=seed，先前從 Demo 入口進來過)：受保護頁面重新整理/深連結時沿用種子 session。
 * - 沒有任何 session 的訪客：只有 Demo 入口（/、/demo）會啟動 Demo 模式；直接開受保護頁面
 *   （例如通知信裡的 /owner/orders/{id}、公開頁導去的 /booking/{id}）走 RequireAuth 導去登入，
 *   不會被偷偷登入成種子帳號。
 */
const PUBLIC_PATH_PATTERNS: RegExp[] = [
  /^\/login\/?$/,
  /^\/register\/?$/,
  /^\/forgot-password\/?$/,
  /^\/reset-password\/?$/,
  /^\/sitter\/[^/]+\/profile\/?$/
];

const DEMO_ENTRY_PATHS = ['/', '/demo', '/demo/'];

export const isPublicPath = (pathname: string): boolean =>
  PUBLIC_PATH_PATTERNS.some((re) => re.test(pathname));

export const shouldSeedAutoLogin = (pathname: string, authMode: string | null): boolean => {
  if (authMode === 'manual') return false;
  if (isPublicPath(pathname)) return false;
  if (authMode === 'seed') return true;
  return DEMO_ENTRY_PATHS.includes(pathname);
};
