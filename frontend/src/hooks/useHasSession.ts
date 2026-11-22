import { useSyncExternalStore } from 'react';
import { SESSION_EXPIRED_EVENT } from '../api/axiosClient';

/**
 * 目前分頁是否有登入 session（公開頁用：PRD-005 匿名瀏覽預約頁）。
 * 現階段 access token 仍在 localStorage（SD-FRONTEND-SPEC §2.1.1 cookie 化尚未上線，見 axiosClient.ts TODO）；
 * cookie 化後改讀 tokenStore / AuthContext，只需改這裡。session 失效（SESSION_EXPIRED_EVENT）與
 * 其他分頁登入 / 登出（storage 事件）都會重新判斷。
 */
const hasSessionSnapshot = (): boolean => !!localStorage.getItem('accessToken');

const subscribe = (onChange: () => void) => {
  window.addEventListener(SESSION_EXPIRED_EVENT, onChange);
  window.addEventListener('storage', onChange);
  return () => {
    window.removeEventListener(SESSION_EXPIRED_EVENT, onChange);
    window.removeEventListener('storage', onChange);
  };
};

export const useHasSession = (): boolean =>
  useSyncExternalStore(subscribe, hasSessionSnapshot, () => false);
