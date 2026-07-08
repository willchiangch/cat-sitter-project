export interface CurrentUser {
  userId: string;
  role: string | null;
}

const decodeJwt = (token: string): { userId?: string; role?: string } | null => {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(
      window
        .atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    return JSON.parse(jsonPayload);
  } catch (e) {
    return null;
  }
};

const FALLBACK_MOCK_USER_ID = '3d498178-14c0-4376-b81e-7fb02e615dda';

/**
 * 從 localStorage 的 accessToken 解出目前登入者的 userId/role。
 * 沒有 token 或解碼失敗時退回 mock UUID，維持 demo/未登入情境下頁面不會整支炸掉。
 */
export const useCurrentUser = (): CurrentUser => {
  const token = localStorage.getItem('accessToken');
  if (!token) {
    return { userId: FALLBACK_MOCK_USER_ID, role: null };
  }
  const decoded = decodeJwt(token);
  return {
    userId: decoded?.userId || FALLBACK_MOCK_USER_ID,
    role: decoded?.role || null
  };
};
