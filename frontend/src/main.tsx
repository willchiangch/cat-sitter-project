import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { registerSW } from 'virtual:pwa-register';
import './styles/global.css';
import AppRoutes from './routes.tsx';
import { RoleProvider } from './contexts/RoleContext';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { isAxiosError } from 'axios';

// 偵測到新部署版本時直接強制 reload，避免開著的分頁繼續吃到舊快取的 app shell
// (舊版 registerSW.js 只單純 register()，沒有更新後 reload，部署後開著的分頁會變空白頁)
registerSW({
  immediate: true,
  onNeedRefresh() {
    window.location.reload();
  }
});

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      staleTime: 5 * 60 * 1000,
      // 401 代表 session 已失效（axiosClient 已試過 refresh），重試只會再打一輪 401 / refresh（驗收 N16）。
      // 其餘錯誤沿用預設最多重試 3 次
      retry: (failureCount, error) =>
        !(isAxiosError(error) && error.response?.status === 401) && failureCount < 3
    }
  }
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RoleProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </RoleProvider>
    </QueryClientProvider>
  </StrictMode>
);
