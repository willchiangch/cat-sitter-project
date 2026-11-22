import { isAxiosError } from 'axios';

/**
 * 後端錯誤回應（SD-GLOBAL-SPEC §2.6）：`{ code, message, payload, traceId, timestamp }`，null 欄位不輸出。
 * 前端一律依 `code` 判斷，不得比對 `message` 文字（SD-ORDER §9.2）。
 */
export interface ApiErrorBody {
  code?: string;
  message?: string;
  payload?: Record<string, unknown>;
  traceId?: string;
  timestamp?: string;
  /** 尚未重建模組的舊格式（過渡相容，新頁面不要讀） */
  error?: string;
}

export interface ApiError {
  /** HTTP 狀態；網路錯誤 / 非 axios 錯誤為 null */
  status: number | null;
  /** 後端 `code`；網路錯誤為 `NETWORK_ERROR`，無法辨識為 `UNKNOWN` */
  code: string;
  /** 後端原始訊息，只供 log / fallback，畫面文案一律由 errorMessages.ts 依 code 決定 */
  serverMessage: string | null;
  payload: Record<string, unknown>;
  /** `Retry-After` 標頭（秒）：409 ORDER_BUSY、429 RATE_LIMITED 會帶 */
  retryAfterSec: number | null;
  traceId: string | null;
}

const parseRetryAfter = (raw: unknown): number | null => {
  if (raw === undefined || raw === null || raw === '') return null;
  const n = Number(raw);
  if (Number.isFinite(n) && n >= 0) return Math.max(1, Math.ceil(n));
  // HTTP-date 形式
  const t = Date.parse(String(raw));
  if (Number.isNaN(t)) return null;
  return Math.max(1, Math.ceil((t - Date.now()) / 1000));
};

/** 解析任何 throw 出來的值為統一的 ApiError（SD-ORDER §9.2 `getApiError`）。 */
export const getApiError = (e: unknown): ApiError => {
  if (isAxiosError(e)) {
    const res = e.response;
    if (!res) {
      return {
        status: null,
        code: 'NETWORK_ERROR',
        serverMessage: e.message ?? null,
        payload: {},
        retryAfterSec: null,
        traceId: null
      };
    }
    const body = (res.data && typeof res.data === 'object' ? res.data : {}) as ApiErrorBody;
    const headers = res.headers as Record<string, unknown> | undefined;
    return {
      status: res.status,
      code: body.code ?? body.error ?? `HTTP_${res.status}`,
      serverMessage: body.message ?? null,
      payload: body.payload && typeof body.payload === 'object' ? body.payload : {},
      retryAfterSec: parseRetryAfter(headers?.['retry-after']),
      traceId: body.traceId ?? null
    };
  }
  if (e instanceof ApiRequestError) return e.apiError;
  return {
    status: null,
    code: 'UNKNOWN',
    serverMessage: e instanceof Error ? e.message : null,
    payload: {},
    retryAfterSec: null,
    traceId: null
  };
};

/** 非 axios 流程（例：直傳 GCS 的 fetch）要丟出 ApiError 時使用。 */
export class ApiRequestError extends Error {
  readonly apiError: ApiError;
  constructor(apiError: ApiError) {
    super(apiError.code);
    this.name = 'ApiRequestError';
    this.apiError = apiError;
  }
}

export const payloadString = (err: ApiError, key: string): string | undefined => {
  const v = err.payload[key];
  return typeof v === 'string' ? v : undefined;
};

export const payloadNumber = (err: ApiError, key: string): number | undefined => {
  const v = err.payload[key];
  return typeof v === 'number' ? v : undefined;
};

/**
 * 尚未重建模組的過渡型別：舊頁面 catch 區塊讀 `err.response?.status`、`err.response?.data?.message`、
 * `err.message` 的形狀（取代 `catch (err: any)`，行為不變）。新頁面一律用 `getApiError` + errorMessages。
 */
export interface LegacyHttpError {
  message?: string;
  response?: { status?: number; data?: { message?: string } };
}
