import { Outlet } from 'react-router-dom';
import AppShell from './components/layout/AppShell';
import AppHeader from './components/layout/AppHeader';

function App() {
  return (
    <AppShell>
      <AppHeader />
      <div style={{ flex: 1, padding: '1rem 0' }}>
        <Outlet />
      </div>
    </AppShell>
  );
}

export default App;
