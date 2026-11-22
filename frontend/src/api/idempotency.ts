import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import axiosClient from './axiosClient';
import { getApiError } from './apiError';

/**
 * 冪等寫入（SD-GLOBAL-SPEC §2.4、SD-ORDER §9.2）：
 * - `Idempotency-Key` 由前端在使用者按下送出時產生（UUID）；同一次送出的自動重試沿用同一把；
 *   使用者修改內容後重新送出 → 呼叫端換新 key（`newIdempotencyKey()`）。
 * - `ORDER_BUSY`：依 `Retry-After` 等待後以相同 key 自動重試一次，仍失敗才拋出。
 * - `IDEMPOTENCY_IN_PROGRESS`：同 key 自動重試（最多 3 次，間隔 1 秒），不提示（PRD-005 EX-13）。
 */
export const IDEMPOTENCY_HEADER = 'Idempotency-Key';

export const newIdempotencyKey = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // 非安全環境（http 區網 IP 開發）沒有 randomUUID：以 getRandomValues 組 v4 UUID
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const MAX_IN_PROGRESS_RETRIES = 3;

export const sendIdempotent = async <T>(
  method: 'post' | 'put' | 'delete',
  url: string,
  key: string,
  data?: unknown,
  config: AxiosRequestConfig = {}
): Promise<T> => {
  let busyRetried = false;
  let inProgressRetries = 0;
  for (;;) {
    try {
      const res: AxiosResponse<T> = await axiosClient.request<T>({
        ...config,
        method,
        url,
        data,
        headers: { ...(config.headers ?? {}), [IDEMPOTENCY_HEADER]: key }
      });
      return res.data;
    } catch (e) {
      const err = getApiError(e);
      if (err.code === 'ORDER_BUSY' && !busyRetried) {
        busyRetried = true;
        await sleep((err.retryAfterSec ?? 1) * 1000);
        continue;
      }
      if (err.code === 'IDEMPOTENCY_IN_PROGRESS' && inProgressRetries < MAX_IN_PROGRESS_RETRIES) {
        inProgressRetries += 1;
        await sleep(1000);
        continue;
      }
      throw e;
    }
  }
};

export const postIdempotent = <T>(
  url: string,
  key: string,
  data?: unknown,
  config?: AxiosRequestConfig
): Promise<T> => sendIdempotent<T>('post', url, key, data, config);

/**
 * 非冪等的整值寫入（PUT / DELETE，後端不收 key）遇到 `ORDER_BUSY` 一樣自動重試一次
 * （SD-FULFILL §9.1「`ORDER_BUSY` 同 key 自動重試一次」；整值覆寫天然可重試）。
 */
export const sendWithBusyRetry = async <T>(
  method: 'put' | 'delete' | 'post',
  url: string,
  data?: unknown
): Promise<T> => {
  try {
    const res = await axiosClient.request<T>({ method, url, data });
    return res.data;
  } catch (e) {
    const err = getApiError(e);
    if (err.code !== 'ORDER_BUSY') throw e;
    await sleep((err.retryAfterSec ?? 1) * 1000);
    const res = await axiosClient.request<T>({ method, url, data });
    return res.data;
  }
};
