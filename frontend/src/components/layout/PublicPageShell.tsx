import { Outlet } from 'react-router-dom';
import AppShell from './AppShell';
import AppHeader from './AppHeader';
import { useRole } from '../../contexts/useRole';
import { useHasSession } from '../../hooks/useHasSession';

/**
 * 匿名可開的站內頁面外框（PRD-005 公開預約頁，SD-ORDER §9.1 `/booking/:sitterId` 匿名 / OWNER）。
 * 不經 RequireAuth：未登入不導去登入頁；頁首（通知鈴鐺、KYC 橫幅都要登入）只在有 session 時顯示。
 * 仍等 RoleContext 的 Demo 自動登入判定完成再渲染，避免 Demo 模式下先以匿名狀態打出 API。
 */
const PublicPageShell = () => {
  const { isAuthLoading } = useRole();
  const hasSession = useHasSession();
  if (isAuthLoading) return null;
  return (
    <AppShell>
      {hasSession && <AppHeader />}
      <div data-testid="app-page-outlet" style={{ flex: 1, padding: '1rem 0' }}>
        <Outlet />
      </div>
    </AppShell>
  );
};

export default PublicPageShell;
