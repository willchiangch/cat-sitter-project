import { useNavigate } from 'react-router-dom';
import { useRole } from '../contexts/RoleContext';

// 測試用的 Mock Sitter / Owner / Visit / Order UUID，僅供這個 demo 導覽頁快速連結範例資料
const mockParams = {
  sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
  ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
  visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
  orderId: 'a1023000-0000-0000-0000-000000000000',
  kycRecordId: 'a1023000-0000-0000-0000-000000000000'
};

function DemoHome() {
  const { currentRole, setRole } = useRole();
  const navigate = useNavigate();

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

        <button className="btn-primary" onClick={() => navigate(`/booking/${mockParams.sitterId}`)}>
          進入預約精靈 (飼主端)
        </button>
        <button className="btn-primary" onClick={() => navigate('/sitter/orders')}>
          進入訂單管理 (保母端)
        </button>
        <button className="btn-primary" onClick={() => navigate('/owner/orders')}>
          進入訂單管理 (飼主端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/pets')}
          data-testid="btn-go-pet-manager"
        >
          進入毛孩管理 (飼主端)
        </button>
        <button className="btn-primary" onClick={() => navigate('/sitter/eval')}>
          進入報價評估 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/sitter/plans')}
          data-testid="btn-go-sitter-plans"
        >
          進入方案設定 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/sitter/gatekeeper')}
          data-testid="btn-go-gatekeeper"
        >
          進入門禁設定 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/sitter/payment-settings')}
          data-testid="btn-go-sitter-payment"
        >
          進入收款設定 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/sitter/profile-settings')}
          data-testid="btn-go-sitter-profile-settings"
        >
          進入公開檔案設定 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/admin/forbidden-keywords')}
          data-testid="btn-go-admin-keywords"
        >
          進入敏感詞管理 (管理端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/admin/subscription')}
          data-testid="btn-go-admin-subscription"
        >
          進入訂閱方案管理 (管理端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/sitter/kyc')}
          data-testid="btn-go-sitter-kyc"
        >
          進入 KYC 認證 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/admin/kyc')}
          data-testid="btn-go-admin-kyc-list"
        >
          進入 KYC 審核清單 (管理端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate(`/care-notes/manage/${mockParams.sitterId}/${mockParams.ownerId}`)}
        >
          進入照護管理 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate(`/care-notes/view/${mockParams.sitterId}/${mockParams.ownerId}`)}
        >
          進入照護檢視 (飼主端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate(`/visit-reports/manage/${mockParams.visitId}`)}
        >
          進入日誌回報 (保母端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate(`/visit-reports/view/${mockParams.visitId}`)}
        >
          進入日誌檢視 (飼主端)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/notifications')}
          data-testid="btn-go-notifications"
        >
          進入通知中心 (共用)
        </button>
        <button
          className="btn-primary"
          onClick={() => navigate('/preferences')}
          data-testid="btn-go-preferences"
        >
          進入通知偏好 (共用)
        </button>

        <div
          style={{
            marginTop: '1.5rem',
            borderTop: '1px solid var(--color-surface-high)',
            paddingTop: '1.5rem'
          }}
        >
          <h4 style={{ margin: '0 0 1rem 0' }}>功能直接 Demo 入口</h4>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <button
              className="btn-primary"
              onClick={() => navigate(`/owner/orders/${mockParams.orderId}`)}
            >
              直接進入訂單詳情 (飼主端)
            </button>
            <button
              className="btn-primary"
              onClick={() => navigate(`/admin/resolve/${mockParams.orderId}`)}
            >
              直接進入爭議調解 (管理端)
            </button>
            <button
              className="btn-primary"
              onClick={() => navigate(`/orders/${mockParams.orderId}/modify`)}
            >
              直接進入變更精靈 (飼主/保母)
            </button>
            <button
              className="btn-primary"
              onClick={() => navigate(`/sitter/orders/${mockParams.orderId}/quote`)}
            >
              直接進入變更報價 (保母端)
            </button>
            <button
              className="btn-primary"
              onClick={() => navigate(`/owner/orders/${mockParams.orderId}/modification-confirm`)}
            >
              直接進入變更確認 (飼主端)
            </button>
            <button
              className="btn-primary"
              onClick={() => navigate(`/admin/kyc/${mockParams.kycRecordId}`)}
            >
              直接進入 KYC 審核詳情 (管理端)
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default DemoHome;
