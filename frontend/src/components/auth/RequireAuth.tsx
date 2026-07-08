import { Navigate, Outlet } from 'react-router-dom';
import { useRole } from '../../contexts/RoleContext';

const RequireAuth = () => {
  const { isAuthLoading } = useRole();
  const token = localStorage.getItem('accessToken');

  // 等 RoleContext 的初始自動登入 (demo/dev 種子帳號或既有 manual session) 判定完成，
  // 避免在 token 尚未寫入 localStorage 前就誤判為未登入而導去 /login
  if (isAuthLoading) {
    return null;
  }
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <Outlet />;
};

export default RequireAuth;
