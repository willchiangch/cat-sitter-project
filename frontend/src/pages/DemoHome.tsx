import { useNavigate } from 'react-router-dom';
import { useRole, type Role } from '../contexts/RoleContext';

// 測試用的 Mock Sitter / Owner / Visit / Order UUID，僅供這個 demo 導覽頁快速連結範例資料
const mockParams = {
  sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
  ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
  visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
  orderId: 'a1023000-0000-0000-0000-000000000000',
  kycRecordId: 'a1023000-0000-0000-0000-000000000000'
};

// 每個入口按鈕實際可用的角色 (依後端 @PreAuthorize / 頁面設計對照)，
// 只顯示「目前角色能測」的按鈕，避免誤按到權限不符的功能而誤以為是功能壞了
type DemoEntry = {
  key: string;
  label: string;
  roles: Role[];
  to: string;
  testId?: string;
};

const allRoles: Role[] = ['sitter', 'client', 'admin'];

const mainEntries: DemoEntry[] = [
  { key: 'booking', label: '進入預約精靈 (飼主端)', roles: ['client'], to: `/booking/${mockParams.sitterId}` },
  { key: 'sitter-orders', label: '進入訂單管理 (保母端)', roles: ['sitter'], to: '/sitter/orders' },
  { key: 'sitter-ledger', label: '進入帳務總覽 (保母端)', roles: ['sitter'], to: '/sitter/ledger', testId: 'btn-go-sitter-ledger' },
  { key: 'owner-orders', label: '進入訂單管理 (飼主端)', roles: ['client'], to: '/owner/orders' },
  { key: 'owner-favorites', label: '進入我的最愛保母 (飼主端)', roles: ['client'], to: '/owner/favorites', testId: 'btn-go-owner-favorites' },
  { key: 'pets', label: '進入毛孩管理 (飼主端)', roles: ['client'], to: '/pets', testId: 'btn-go-pet-manager' },
  { key: 'sitter-plans', label: '進入方案設定 (保母端)', roles: ['sitter'], to: '/sitter/plans', testId: 'btn-go-sitter-plans' },
  { key: 'sitter-questions', label: '進入事前問卷設定 (保母端)', roles: ['sitter'], to: '/sitter/questions', testId: 'btn-go-sitter-questions' },
  { key: 'sitter-trust-circle', label: '進入信任圈 (保母端)', roles: ['sitter'], to: '/sitter/trust-circle', testId: 'btn-go-sitter-trust-circle' },
  { key: 'gatekeeper', label: '進入門禁設定 (保母端)', roles: ['sitter'], to: '/sitter/gatekeeper', testId: 'btn-go-gatekeeper' },
  { key: 'sitter-payment', label: '進入收款設定 (保母端)', roles: ['sitter'], to: '/sitter/payment-settings', testId: 'btn-go-sitter-payment' },
  { key: 'sitter-profile', label: '進入公開檔案設定 (保母端)', roles: ['sitter'], to: '/sitter/profile-settings', testId: 'btn-go-sitter-profile-settings' },
  { key: 'admin-keywords', label: '進入敏感詞管理 (管理端)', roles: ['admin'], to: '/admin/forbidden-keywords', testId: 'btn-go-admin-keywords' },
  { key: 'admin-subscription', label: '進入訂閱方案管理 (管理端)', roles: ['admin'], to: '/admin/subscription', testId: 'btn-go-admin-subscription' },
  { key: 'sitter-kyc', label: '進入 KYC 認證 (保母端)', roles: ['sitter'], to: '/sitter/kyc', testId: 'btn-go-sitter-kyc' },
  { key: 'admin-kyc-list', label: '進入 KYC 審核清單 (管理端)', roles: ['admin'], to: '/admin/kyc', testId: 'btn-go-admin-kyc-list' },
  { key: 'admin-trust-scores', label: '進入信用指標管理 (管理端)', roles: ['admin'], to: '/admin/trust-scores', testId: 'btn-go-admin-trust-scores' },
  { key: 'care-notes-manage', label: '進入照護管理 (保母端)', roles: ['sitter'], to: `/care-notes/manage/${mockParams.sitterId}/${mockParams.ownerId}` },
  { key: 'care-notes-view', label: '進入照護檢視 (飼主端)', roles: ['client'], to: `/care-notes/view/${mockParams.sitterId}/${mockParams.ownerId}` },
  { key: 'visit-reports-manage', label: '進入日誌回報 (保母端)', roles: ['sitter'], to: `/visit-reports/manage/${mockParams.visitId}` },
  { key: 'visit-reports-view', label: '進入日誌檢視 (飼主端)', roles: ['client'], to: `/visit-reports/view/${mockParams.visitId}` },
  { key: 'notifications', label: '進入通知中心 (共用)', roles: allRoles, to: '/notifications', testId: 'btn-go-notifications' },
  { key: 'preferences', label: '進入通知偏好 (共用)', roles: allRoles, to: '/preferences', testId: 'btn-go-preferences' }
];

const quickEntries: DemoEntry[] = [
  // 訂單詳情頁後端為 hasAnyRole('OWNER','SITTER')，飼主/保母皆可查看，非飼主獨有
  { key: 'order-detail', label: '直接進入訂單詳情 (飼主端)', roles: ['client', 'sitter'], to: `/owner/orders/${mockParams.orderId}` },
  { key: 'admin-resolve', label: '直接進入爭議調解 (管理端)', roles: ['admin'], to: `/admin/resolve/${mockParams.orderId}` },
  { key: 'order-modify', label: '直接進入變更精靈 (飼主/保母)', roles: ['client', 'sitter'], to: `/orders/${mockParams.orderId}/modify` },
  { key: 'sitter-quote', label: '直接進入變更報價 (保母端)', roles: ['sitter'], to: `/sitter/orders/${mockParams.orderId}/quote` },
  { key: 'sitter-eval', label: '直接進入報價評估 (保母端)', roles: ['sitter'], to: `/sitter/eval/${mockParams.orderId}` },
  { key: 'owner-modify-confirm', label: '直接進入變更確認 (飼主端)', roles: ['client'], to: `/owner/orders/${mockParams.orderId}/modification-confirm` },
  { key: 'admin-kyc-detail', label: '直接進入 KYC 審核詳情 (管理端)', roles: ['admin'], to: `/admin/kyc/${mockParams.kycRecordId}` },
  { key: 'sitter-referral', label: '直接進入不接單並轉介 (保母端)', roles: ['sitter'], to: `/sitter/referral/${mockParams.ownerId}` }
];

function DemoHome() {
  const { currentRole, setRole } = useRole();
  const navigate = useNavigate();
  const visibleMainEntries = mainEntries.filter((entry) => entry.roles.includes(currentRole));
  const visibleQuickEntries = quickEntries.filter((entry) => entry.roles.includes(currentRole));

  return (
    <div style={{ padding: '2rem 0', textAlign: 'center' }}>
      <h1 style={{ color: 'var(--color-primary)', fontSize: '2.5rem', marginBottom: '1rem' }}>
        WhiskerWatch
      </h1>
      <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2.5rem' }}>
        當前角色:{' '}
        <strong style={{ color: 'var(--color-on-surface)' }}>
          {currentRole === 'sitter' ? '貓咪保母' : currentRole === 'client' ? '愛貓飼主' : '系統管理員'}
        </strong>
      </p>
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem',
          maxWidth: '340px',
          margin: '0 auto'
        }}
      >
        <div
          style={{
            display: 'flex',
            gap: '0.5rem',
            justifyContent: 'center',
            marginBottom: '1rem'
          }}
        >
          <button
            className="btn-primary"
            onClick={() => setRole('sitter')}
            style={{
              opacity: currentRole === 'sitter' ? 1 : 0.6,
              fontSize: '0.8rem',
              padding: '8px 12px'
            }}
          >
            切換為保母
          </button>
          <button
            className="btn-primary"
            data-testid="btn-role-toggle"
            onClick={() => setRole('client')}
            style={{
              opacity: currentRole === 'client' ? 1 : 0.6,
              fontSize: '0.8rem',
              padding: '8px 12px'
            }}
          >
            切換為飼主
          </button>
          <button
            className="btn-primary"
            onClick={() => setRole('admin')}
            style={{
              opacity: currentRole === 'admin' ? 1 : 0.6,
              fontSize: '0.8rem',
              padding: '8px 12px'
            }}
          >
            切換為管理員
          </button>
        </div>

        {visibleMainEntries.map((entry) => (
          <button
            key={entry.key}
            className="btn-primary"
            onClick={() => navigate(entry.to)}
            {...(entry.testId ? { 'data-testid': entry.testId } : {})}
          >
            {entry.label}
          </button>
        ))}

        {visibleQuickEntries.length > 0 && (
          <div
            style={{
              marginTop: '1.5rem',
              borderTop: '1px solid var(--color-surface-high)',
              paddingTop: '1.5rem'
            }}
          >
            <h4 style={{ margin: '0 0 1rem 0' }}>功能直接 Demo 入口</h4>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              {visibleQuickEntries.map((entry) => (
                <button key={entry.key} className="btn-primary" onClick={() => navigate(entry.to)}>
                  {entry.label}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default DemoHome;
