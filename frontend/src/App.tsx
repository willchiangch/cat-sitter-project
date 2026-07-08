import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import AppShell from './components/layout/AppShell';
import AppHeader from './components/layout/AppHeader';

function App() {
  const navigate = useNavigate();
  const location = useLocation();

  return (
    <AppShell>
      <AppHeader />
      {location.pathname !== '/demo' && (
        <button
          onClick={() => navigate('/demo')}
          style={{
            position: 'absolute',
            top: '120px',
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
      <div style={{ flex: 1, padding: '1rem 0' }}>
        <Outlet />
      </div>
    </AppShell>
  );
}

export default App;
