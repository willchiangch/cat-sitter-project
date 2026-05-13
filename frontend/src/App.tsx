import { useState } from 'react';
import AppShell from './components/layout/AppShell';
import { useRole } from './contexts/RoleContext';
import SitterOrders from './pages/sitter/SitterOrders';
import OrderEvalView from './pages/sitter/OrderEvalView';
import PublicBookingPage from './pages/client/PublicBookingPage';

function App() {
  const { currentRole, toggleRole } = useRole();
  const [view, setView] = useState<'demo' | 'orders' | 'eval' | 'booking'>('demo');

  const renderView = () => {
    switch (view) {
      case 'orders':
        return <SitterOrders />;
      case 'eval':
        return <OrderEvalView />;
      case 'booking':
        return <PublicBookingPage />;
      default:
        return (
          <div style={{ padding: '2rem 0', textAlign: 'center' }}>
            <h1 style={{ color: 'var(--color-primary)', fontSize: '2.5rem', marginBottom: '1rem' }}>WhiskerWatch</h1>
            <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2.5rem' }}>
              當前角色: <strong style={{ color: 'var(--color-on-surface)' }}>{currentRole === 'sitter' ? '貓咪保母' : '愛貓飼主'}</strong>
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <button className="btn-primary" onClick={toggleRole} data-testid="btn-role-toggle">
                切換角色 (切換主題色)
              </button>
              <button className="btn-primary" onClick={() => setView('booking')}>
                進入預約精靈 (飼主端)
              </button>
              <button className="btn-primary" onClick={() => setView('orders')}>
                進入訂單管理 (保母端)
              </button>
              <button className="btn-primary" onClick={() => setView('eval')}>
                進入報價評估 (保母端)
              </button>
            </div>
          </div>
        );
    }
  };

  return (
    <AppShell>
      {view !== 'demo' && (
        <button
          onClick={() => setView('demo')}
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
