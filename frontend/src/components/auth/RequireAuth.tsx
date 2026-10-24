import { useEffect, useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useRole } from '../../contexts/RoleContext';
import { SESSION_EXPIRED_EVENT } from '../../api/axiosClient';

const RequireAuth = () => {
  const { isAuthLoading } = useRole();
  const location = useLocation();
  // axiosClient 在 refresh 失敗清掉 token 後廣播 SESSION_EXPIRED_EVENT；這裡只靠 location 變化重新判斷
  // token，停在原頁不換頁就不會重新 render，畫面會停在「$ 0 + 載入失敗」（驗收 N16）。收到事件強制重新判斷，
  // 走下面同一條導去 /login?redirect= 的路
  const [, setSessionVersion] = useState(0);
  useEffect(() => {
    const onExpired = () => setSessionVersion((v) => v + 1);
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, []);
  const token = localStorage.getItem('accessToken');

  // 等 RoleContext 的初始自動登入 (demo/dev 種子帳號或既有 manual session) 判定完成，
  // 避免在 token 尚未寫入 localStorage 前就誤判為未登入而導去 /login
  if (isAuthLoading) {
    return null;
  }
  if (!token) {
    // 帶 redirect 回原頁：匿名訪客直接開受保護頁面（例如 /booking/{sitterId}）不再被種子帳號
    // 自動登入，登入後要能回到原本要去的地方（LoginPage 只接受同源相對路徑）
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  return <Outlet />;
};

export default RequireAuth;
