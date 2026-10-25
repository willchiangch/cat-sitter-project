import { ROLE_CLAIM_TO_APP_ROLE, decodeTokenRole, type AppRole } from './manualRoleSwitch';

/**
 * 登入 / 註冊成功後決定 localStorage.userRole（RoleContext 換頁後由此初始化）。
 *
 * fix-batch12 J5：過去 LoginPage / RegisterPage 只從 JWT 的 role claim 解析、忽略回應本身的 role 欄位；
 * 解不出（token 不是 JWT、claim 缺漏或值不認得）時不寫也不清 userRole，RoleContext 就沿用上一位使用者
 * 留下的角色（session 失效時 axiosClient 只清 token、不清 userRole）。改成：
 * 1. 以回應的 role 為準（/auth/login、/auth/google、/auth/register/verify-otp、WebAuthn 都會回）
 * 2. 回應沒有可辨識的 role 才退回 JWT claim
 * 3. 兩者都解不出 → 移除舊的 userRole，絕不沿用上一位使用者的角色
 */
export const applyLoginRole = (data: {
  role?: unknown;
  accessToken?: string | null;
}): AppRole | null => {
  const fromResponse =
    typeof data.role === 'string' ? (ROLE_CLAIM_TO_APP_ROLE[data.role] ?? null) : null;
  const appRole = fromResponse ?? decodeTokenRole(data.accessToken ?? null);
  if (appRole) {
    localStorage.setItem('userRole', appRole);
  } else {
    localStorage.removeItem('userRole');
  }
  return appRole;
};
