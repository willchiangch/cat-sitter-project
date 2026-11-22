import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { registerSW } from 'virtual:pwa-register';
import './styles/global.css';
import AppRoutes from './routes.tsx';
import { RoleProvider } from './contexts/RoleContext';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { getApiError } from './api/apiError';

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
      // 其他 4xx（403 / 404 / 409 / 422）是確定性錯誤，重試不會改變結果；429 RATE_LIMITED 依 Retry-After
      // 延遲後最多重試 1 次（SD-GLOBAL-SPEC §2.6）；網路錯誤與 5xx 最多重試 3 次
      retry: (failureCount, error) => {
        if (!isAxiosError(error) || !error.response) return failureCount < 3;
        const status = error.response.status;
        if (status === 429) return failureCount < 1;
        if (status >= 400 && status < 500) return false;
        return failureCount < 3;
      },
      retryDelay: (attempt, error) => {
        const retryAfter = getApiError(error).retryAfterSec;
        if (retryAfter) return retryAfter * 1000;
        return Math.min(1000 * 2 ** attempt, 30000);
      }
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
