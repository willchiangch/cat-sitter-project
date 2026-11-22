import { useEffect, useRef, useState } from 'react';
import {
  createLatestDebounced,
  type LatestDebounced,
  type LatestState
} from '../utils/latestDebounced';

/**
 * 輸入（以 JSON 內容比較）變動後防抖呼叫 `fetch`，只採最後一次回應（SD-ORDER §9.2 變更金額試算）。
 * `input` 為 null 表示輸入不完整，不呼叫並回 idle。
 */
export const useLatestDebounced = <I, T>(
  input: I | null,
  fetch: (input: I, signal: AbortSignal) => Promise<T>,
  delayMs = 400
): LatestState<T> => {
  const [state, setState] = useState<LatestState<T>>({ status: 'idle' });
  const fetchRef = useRef(fetch);
  const ctrlRef = useRef<LatestDebounced<I> | null>(null);
  const key = input === null ? null : JSON.stringify(input);

  useEffect(() => {
    fetchRef.current = fetch;
  });

  useEffect(() => {
    const d = createLatestDebounced<I, T>({
      delayMs,
      fetch: (i, signal) => fetchRef.current(i, signal),
      onState: setState
    });
    ctrlRef.current = d;
    return () => {
      d.dispose();
      ctrlRef.current = null;
    };
  }, [delayMs]);

  useEffect(() => {
    ctrlRef.current?.schedule(key === null ? null : (JSON.parse(key) as I));
  }, [key, delayMs]);

  return state;
};
