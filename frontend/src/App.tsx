import { useState, useEffect } from 'react';
import AppShell from './components/layout/AppShell';
import { useRole } from './contexts/RoleContext';
import SitterOrders from './pages/sitter/SitterOrders';
import OrderEvalView from './pages/sitter/OrderEvalView';
import PublicBookingPage from './pages/client/PublicBookingPage';
import CareNoteManager from './pages/sitter/CareNoteManager';
import CareNoteView from './pages/client/CareNoteView';

type ViewState = {
  name: 'demo' | 'orders' | 'eval' | 'booking' | 'carenote-manager' | 'carenote-view';
  params?: { sitterId: string; ownerId: string };
};

function App() {
  const { currentRole, toggleRole } = useRole();
  const [view, setView] = useState<ViewState>({ name: 'demo' });
  const [authReady, setAuthReady] = useState(false);

  // 測試用的 Mock Sitter / Owner UUID
  const mockParams = {
    sitterId: '3d498178-14c0-4376-b81e-7fb02e615dda',
    ownerId: '1031efbc-583a-4062-9a35-15706a3384c6'
  };

  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    if (!token) {
      // 在本地開發環境下自動登入以獲取 JWT Token
      fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: 'sitter@test.com',
          password: 'password'
        })
      })
        .then((res) => res.json())
        .then((data) => {
          if (data.accessToken) {
            localStorage.setItem('accessToken', data.accessToken);
            localStorage.setItem('refreshToken', data.refreshToken);
            setAuthReady(true);
          } else {
            console.error('自動登入失敗：無 token 資料');
            setAuthReady(true);
          }
        })
        .catch((err) => {
          console.error('自動登入發生錯誤：', err);
          setAuthReady(true);
        });
    } else {
      setAuthReady(true);
    }
  }, []);

  const renderView = () => {
    switch (view.name) {
      case 'orders':
        return <SitterOrders />;
      case 'eval':
        return <OrderEvalView />;
      case 'booking':
        return <PublicBookingPage />;
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
      default:
        return (
          <div style={{ padding: '2rem 0', textAlign: 'center' }}>
            <h1 style={{ color: 'var(--color-primary)', fontSize: '2.5rem', marginBottom: '1rem' }}>WhiskerWatch</h1>
            <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2.5rem' }}>
              當前角色: <strong style={{ color: 'var(--color-on-surface)' }}>{currentRole === 'sitter' ? '貓咪保母' : '愛貓飼主'}</strong>
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', maxWidth: '300px', margin: '0 auto' }}>
              <button className="btn-primary" onClick={toggleRole} data-testid="btn-role-toggle">
                切換角色 (切換主題色)
              </button>
              <button className="btn-primary" onClick={() => setView({ name: 'booking' })}>
                進入預約精靈 (飼主端)
              </button>
              <button className="btn-primary" onClick={() => setView({ name: 'orders' })}>
                進入訂單管理 (保母端)
              </button>
              <button className="btn-primary" onClick={() => setView({ name: 'eval' })}>
                進入報價評估 (保母端)
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
            </div>
          </div>
        );
    }
  };

  if (!authReady) {
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

