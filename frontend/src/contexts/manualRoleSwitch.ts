import axiosClient from '../api/axiosClient';

export type AppRole = 'sitter' | 'client' | 'admin';

/**
 * 正式登入 (authMode=manual) 的使用者在 /demo 切換角色 (SD-001 §切換角色，Backlog 第 3 批 D3)。
 *
 * 過去 setRole() 不看 authMode，一律改登入種子帳號：真實使用者/管理員點「切換為保母」會被
 * 換成 sitter@test.com 的 session，自己的帳號被登出、之後的操作都記在種子帳號名下。
 * manual 模式改走真實的 POST /api/auth/switch-role（對目前帳號切換，後端 lazy 建立該角色 Profile，
 * 簽發新 token），不會碰種子帳號：
 * - 目標角色就是目前 token 的角色 → 不送請求
 * - 管理員不是 Profile 角色（switch-role 只接受 SITTER / CLIENT）：非管理員帳號不能切成管理員，
 *   管理員帳號也沒有保母/飼主身分可切 → 丟 RoleSwitchError，不改任何 session
 */
export class RoleSwitchError extends Error {}

export const ROLE_CLAIM_TO_APP_ROLE: Record<string, AppRole> = {
  OWNER: 'client',
  CLIENT: 'client',
  SITTER: 'sitter',
  ADMIN: 'admin'
};

export const decodeTokenRole = (token: string | null): AppRole | null => {
  if (!token) return null;
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(
      window
        .atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    const claim = JSON.parse(json)?.role;
    return typeof claim === 'string' ? (ROLE_CLAIM_TO_APP_ROLE[claim] ?? null) : null;
  } catch {
    return null;
  }
};

export const switchManualRole = async (role: AppRole): Promise<void> => {
  const current = decodeTokenRole(localStorage.getItem('accessToken'));
  if (!current) {
    throw new RoleSwitchError('登入狀態已失效，請重新登入後再切換角色');
  }
  if (current === role) return;
  if (role === 'admin') {
    throw new RoleSwitchError('此帳號沒有管理員權限，無法切換為管理員');
  }
  if (current === 'admin') {
    throw new RoleSwitchError('管理員帳號沒有保母/飼主身分，無法切換');
  }

  const response = await axiosClient.post('/auth/switch-role', {
    targetRole: role === 'sitter' ? 'SITTER' : 'CLIENT'
  });
  const { accessToken, refreshToken } = response.data ?? {};
  if (!accessToken) {
    throw new RoleSwitchError('切換角色失敗，請稍後再試');
  }
  localStorage.setItem('accessToken', accessToken);
  localStorage.setItem('refreshToken', refreshToken || '');
  localStorage.setItem('authMode', 'manual');
};
