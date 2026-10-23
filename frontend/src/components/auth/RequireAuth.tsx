import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useRole } from '../../contexts/RoleContext';

const RequireAuth = () => {
  const { isAuthLoading } = useRole();
  const location = useLocation();
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
