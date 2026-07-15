import { Navigate, Outlet } from 'react-router-dom';
import { useRole, type Role } from '../../contexts/RoleContext';

interface RequireRoleProps {
  roles: Role[];
}

// 用 RoleContext.currentRole 而非直接 decode JWT：LoginPage 真實登入時已把 JWT role claim
// 正確映射進 currentRole，demo 角色切換也是靠它驅動，是全站唯一一致的角色訊號來源。
// 後端每支 API 仍有各自的 @PreAuthorize 把關，這裡只是前端路由層的防呆。
const RequireRole = ({ roles }: RequireRoleProps) => {
  const { currentRole } = useRole();

  if (!roles.includes(currentRole)) {
    return <Navigate to="/demo" replace />;
  }

  return <Outlet />;
};

export default RequireRole;
