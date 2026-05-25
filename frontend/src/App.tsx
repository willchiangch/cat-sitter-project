import { useState } from 'react';
import AppShell from './components/layout/AppShell';
import { useRole } from './contexts/RoleContext';
import SitterOrders from './pages/sitter/SitterOrders';
import OrderEvalView from './pages/sitter/OrderEvalView';
import PublicBookingPage from './pages/client/PublicBookingPage';
import CareNoteManager from './pages/sitter/CareNoteManager';
import CareNoteView from './pages/client/CareNoteView';
import VisitReportManager from './pages/sitter/VisitReportManager';
import VisitReportView from './pages/client/VisitReportView';

// 新增頁面
import OwnerOrders from './pages/client/OwnerOrders';
import OwnerOrderDetail from './pages/client/OwnerOrderDetail';
import AdminResolvePanel from './pages/admin/AdminResolvePanel';
import OrderModificationWizard from './pages/client/OrderModificationWizard';
import SitterModificationQuote from './pages/sitter/SitterModificationQuote';
import OwnerModificationConfirm from './pages/client/OwnerModificationConfirm';
import SitterPlans from './pages/sitter/SitterPlans';

type ViewState = {
  name: 'demo' | 'orders' | 'eval' | 'booking' | 'carenote-manager' | 'carenote-view' | 'visit-report-manager' | 'visit-report-view'
    | 'owner-orders' | 'owner-order-detail' | 'admin-resolve' | 'modification-wizard' | 'sitter-modification-quote' | 'owner-modification-confirm' | 'sitter-plans';
  params?: { sitterId: string; ownerId: string; visitId?: string; orderId?: string };
};

function App() {
  const { currentRole, setRole, isAuthLoading } = useRole();
  const [view, setView] = useState<ViewState>({ name: 'demo' });

  // 測試用的 Mock Sitter / Owner / Visit / Order UUID
  const mockParams = {
    sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
    ownerId: '1031efbc-583a-4062-9a35-15706a3384c6',
    visitId: '2624511e-3f10-4376-b81e-7fb02e615dda',
    orderId: 'a1023000-0000-0000-0000-000000000000'
  };

  const renderView = () => {
    switch (view.name) {
      case 'orders':
        return <SitterOrders />;
      case 'eval':
        return <OrderEvalView />;
      case 'booking':
        return <PublicBookingPage sitterId={view.params?.sitterId || mockParams.sitterId} />;
      case 'sitter-plans':
        return <SitterPlans />;
      case 'carenote-manager':
        return (
          <CareNoteManager
            sitterId={view.params?.sitterId || mockParams.sitterId}
            ownerId={view.params?.ownerId || mockParams.ownerId}
          />
        );
      case 'carenote-view':
        return (
          <CareNoteView
            sitterId={view.params?.sitterId || mockParams.sitterId}
            ownerId={view.params?.ownerId || mockParams.ownerId}
          />
        );
      case 'visit-report-manager':
        return (
          <VisitReportManager
            visitId={view.params?.visitId || mockParams.visitId}
          />
        );
      case 'visit-report-view':
        return (
          <VisitReportView
            visitId={view.params?.visitId || mockParams.visitId}
          />
        );
      case 'owner-orders':
        return <OwnerOrders setView={setView} />;
      case 'owner-order-detail':
        return <OwnerOrderDetail orderId={view.params?.orderId || mockParams.orderId} setView={setView} />;
      case 'admin-resolve':
        return <AdminResolvePanel orderId={view.params?.orderId || mockParams.orderId} />;
      case 'modification-wizard':
        return <OrderModificationWizard orderId={view.params?.orderId || mockParams.orderId} />;
      case 'sitter-modification-quote':
        return <SitterModificationQuote orderId={view.params?.orderId || mockParams.orderId} />;
      case 'owner-modification-confirm':
        return <OwnerModificationConfirm orderId={view.params?.orderId || mockParams.orderId} />;
      default:
        return (
          <div style={{ padding: '2rem 0', textAlign: 'center' }}>
            <h1 style={{ color: 'var(--color-primary)', fontSize: '2.5rem', marginBottom: '1rem' }}>WhiskerWatch</h1>
            <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2.5rem' }}>
              當前角色: <strong style={{ color: 'var(--color-on-surface)' }}>
                {currentRole === 'sitter' ? '貓咪保母' : currentRole === 'client' ? '愛貓飼主' : '系統管理員'}
              </strong>
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', maxWidth: '340px', margin: '0 auto' }}>
              <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'center', marginBottom: '1rem' }}>
                <button 
                  className="btn-primary" 
                  onClick={() => setRole('sitter')} 
                  style={{ opacity: currentRole === 'sitter' ? 1 : 0.6, fontSize: '0.8rem', padding: '8px 12px' }}
                >
                  切換為保母
                </button>
                <button 
                  className="btn-primary" 
                  onClick={() => setRole('client')}
                  style={{ opacity: currentRole === 'client' ? 1 : 0.6, fontSize: '0.8rem', padding: '8px 12px' }}
                >
                  切換為飼主
                </button>
                <button 
                  className="btn-primary" 
                  onClick={() => setRole('admin')}
                  style={{ opacity: currentRole === 'admin' ? 1 : 0.6, fontSize: '0.8rem', padding: '8px 12px' }}
                >
                  切換為管理員
                </button>
              </div>

              <button className="btn-primary" onClick={() => setView({ name: 'booking' })}>
                進入預約精靈 (飼主端)
              </button>
              <button className="btn-primary" onClick={() => setView({ name: 'orders' })}>
                進入訂單管理 (保母端)
              </button>
              <button className="btn-primary" onClick={() => setView({ name: 'owner-orders' })}>
                進入訂單管理 (飼主端)
              </button>
              <button className="btn-primary" onClick={() => setView({ name: 'eval' })}>
                進入報價評估 (保母端)
              </button>
              <button 
                className="btn-primary" 
                onClick={() => setView({ name: 'sitter-plans' })}
                data-testid="btn-go-sitter-plans"
              >
                進入方案設定 (保母端)
              </button>
              <button
                className="btn-primary"
                onClick={() => setView({ name: 'carenote-manager', params: mockParams })}
              >
                進入照護管理 (保母端)
              </button>
              <button
                className="btn-primary"
                onClick={() => setView({ name: 'carenote-view', params: mockParams })}
              >
                進入照護檢視 (飼主端)
              </button>
              <button
                className="btn-primary"
                onClick={() => setView({ name: 'visit-report-manager', params: mockParams })}
              >
                進入日誌回報 (保母端)
              </button>
              <button
                className="btn-primary"
                onClick={() => setView({ name: 'visit-report-view', params: mockParams })}
              >
                進入日誌檢視 (飼主端)
              </button>

              <div style={{ marginTop: '1.5rem', borderTop: '1px solid var(--color-surface-high)', paddingTop: '1.5rem' }}>
                <h4 style={{ margin: '0 0 1rem 0' }}>功能直接 Demo 入口</h4>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <button className="btn-primary" onClick={() => setView({ name: 'owner-order-detail', params: mockParams })}>
                    直接進入訂單詳情 (飼主端)
                  </button>
                  <button className="btn-primary" onClick={() => setView({ name: 'admin-resolve', params: mockParams })}>
                    直接進入爭議調解 (管理端)
                  </button>
                  <button className="btn-primary" onClick={() => setView({ name: 'modification-wizard', params: mockParams })}>
                    直接進入變更精靈 (飼主/保母)
                  </button>
                  <button className="btn-primary" onClick={() => setView({ name: 'sitter-modification-quote', params: mockParams })}>
                    直接進入變更報價 (保母端)
                  </button>
                  <button className="btn-primary" onClick={() => setView({ name: 'owner-modification-confirm', params: mockParams })}>
                    直接進入變更確認 (飼主端)
                  </button>
                </div>
              </div>
            </div>
          </div>
        );
    }
  };

  if (isAuthLoading) {
    return <div style={{ padding: '2rem', textAlign: 'center' }}>正在載入安全憑證與使用者資料...</div>;
  }

  return (
    <AppShell>
      {view.name !== 'demo' && (
        <button
          onClick={() => setView({ name: 'demo' })}
          style={{
            position: 'absolute',
            top: '16px',
            right: '16px',
            zIndex: 10,
            border: 'none',
            background: '#eee',
            padding: '4px 8px',
            borderRadius: '4px',
            fontSize: '10px',
            cursor: 'pointer'
          }}
        >
          返回 Demo 首頁
        </button>
      )}
      {renderView()}
    </AppShell>
  );
}

export default App;
