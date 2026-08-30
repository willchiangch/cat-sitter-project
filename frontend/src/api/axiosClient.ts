import axios from 'axios';
import type { AxiosError, InternalAxiosRequestConfig } from 'axios';

const axiosClient = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json'
  }
});

let isRefreshing = false;
let failedQueue: { resolve: (token: string | null) => void; reject: (error: unknown) => void }[] =
  [];

const processQueue = (error: unknown, token: string | null = null) => {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token);
    }
  });
  failedQueue = [];
};

// 這些端點本身就是「登入前/未認證」流程，401 代表的是帳密錯誤、OTP 錯誤等真正的業務
// 失敗，不是「access token 過期」。過去沒有排除這些端點，導致登入打錯密碼時，攔截器誤判
// 成 token 過期，拿 localStorage 裡殘留、跟這次登入無關的 refreshToken 去打 /auth/refresh，
// 真正的登入失敗原因被這次多餘的 refresh 呼叫蓋掉（refresh 失敗顯示成不相干的 500 或
// 「must not be blank」，使用者完全看不到「帳號或密碼錯誤」）。
const PUBLIC_AUTH_PATH_PREFIXES = [
  '/auth/login',
  '/auth/register',
  '/auth/refresh',
  '/auth/forgot-password',
  '/auth/reset-password',
  '/auth/google',
  '/auth/webauthn/login'
];

const isPublicAuthEndpoint = (url?: string): boolean => {
  if (!url) return false;
  const path = url.startsWith('http') ? new URL(url).pathname.replace(/^\/api/, '') : url;
  return PUBLIC_AUTH_PATH_PREFIXES.some((prefix) => path.startsWith(prefix));
};

// Request Interceptor
axiosClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    // axios instance 預設帶 Content-Type: application/json；當 body 是 FormData 時，
    // axios 的 transformRequest 一偵測到 Content-Type 含 application/json 就會把 FormData
    // 轉成 JSON.stringify(formDataToJSON(data))，File 物件序列化後變成 {}，等於整包檔案內容
    // 憑空消失，後端收到的永遠是空檔案。這裡統一刪掉 Content-Type，讓瀏覽器自己補上正確
    // 帶 boundary 的 multipart/form-data，不用每個上傳呼叫點各自記得覆寫。
    if (config.data instanceof FormData) {
      config.headers.delete('Content-Type');
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response Interceptor with Concurrency Lock
axiosClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean };

    if (
      error.response?.status === 401 &&
      !originalRequest._retry &&
      !isPublicAuthEndpoint(originalRequest.url)
    ) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        })
          .then((token) => {
            originalRequest.headers.Authorization = `Bearer ${token}`;
            return axiosClient(originalRequest);
          })
          .catch((err) => Promise.reject(err));
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const refreshToken = localStorage.getItem('refreshToken');
        // 呼叫後端刷新介面 (需對應 SD-000)
        const response = await axios.post('/api/auth/refresh', { refreshToken });
        const { accessToken, refreshToken: newRefreshToken } = response.data;

        localStorage.setItem('accessToken', accessToken);
        if (newRefreshToken) {
          localStorage.setItem('refreshToken', newRefreshToken);
        }

        axiosClient.defaults.headers.common['Authorization'] = `Bearer ${accessToken}`;
        processQueue(null, accessToken);

        return axiosClient(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

export default axiosClient;
