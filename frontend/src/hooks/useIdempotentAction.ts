import { useCallback, useRef, useState } from 'react';
import { getApiError } from '../api/apiError';
import { newIdempotencyKey } from '../api/idempotency';

/**
 * 冪等寫入的按鈕狀態（SD-ORDER §9.2）：
 * - 按下送出時產生 key；網路錯誤 / 5xx / ORDER_BUSY（冪等不記錄）時保留同一把，使用者重按即同 key 重試
 * - 收到確定性業務錯誤（4xx，冪等已記錄錯誤回應）後丟棄 key，使用者修改內容後重送會換新 key
 * - pending 期間呼叫端應停用按鈕防重送
 */
export const useIdempotentAction = () => {
  const keyRef = useRef<string | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const run = useCallback(async <T>(fn: (key: string) => Promise<T>): Promise<T | undefined> => {
    setError(null);
    setPending(true);
    keyRef.current = keyRef.current ?? newIdempotencyKey();
    try {
      const result = await fn(keyRef.current);
      keyRef.current = null;
      return result;
    } catch (e) {
      const err = getApiError(e);
      const retryable =
        err.status === null ||
        err.status >= 500 ||
        err.code === 'ORDER_BUSY' ||
        err.code === 'IDEMPOTENCY_IN_PROGRESS' ||
        err.code === 'RATE_LIMITED';
      if (!retryable) keyRef.current = null;
      setError(e);
      return undefined;
    } finally {
      setPending(false);
    }
  }, []);

  const reset = useCallback(() => {
    keyRef.current = null;
    setError(null);
  }, []);

  return { run, pending, error, setError, reset };
};
