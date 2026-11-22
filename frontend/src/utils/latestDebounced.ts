/**
 * 防抖 + 只採最後一次請求（SD-ORDER §9.2「變更金額一律取伺服器試算」）：
 * - `schedule(input)`：取消尚未送出的計時與進行中的請求，立即回報 `loading`，`delayMs` 後才送出
 * - 較早的請求晚到（成功或失敗）一律丟棄；被取消的請求不回報
 * - `schedule(null)`：輸入不完整，不送請求，回報 `idle`
 */
export type LatestState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: T }
  | { status: 'error'; error: unknown };

export interface LatestDebounced<I> {
  schedule: (input: I | null) => void;
  dispose: () => void;
}

export const createLatestDebounced = <I, T>(opts: {
  delayMs: number;
  fetch: (input: I, signal: AbortSignal) => Promise<T>;
  onState: (state: LatestState<T>) => void;
}): LatestDebounced<I> => {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let controller: AbortController | null = null;
  let seq = 0;
  let disposed = false;

  const cancel = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    controller?.abort();
    controller = null;
  };

  const schedule = (input: I | null) => {
    if (disposed) return;
    cancel();
    const id = ++seq;
    if (input === null) {
      opts.onState({ status: 'idle' });
      return;
    }
    opts.onState({ status: 'loading' });
    timer = setTimeout(() => {
      timer = null;
      const ctrl = new AbortController();
      controller = ctrl;
      opts.fetch(input, ctrl.signal).then(
        (data) => {
          if (disposed || id !== seq) return;
          controller = null;
          opts.onState({ status: 'success', data });
        },
        (error: unknown) => {
          if (disposed || id !== seq || ctrl.signal.aborted) return;
          controller = null;
          opts.onState({ status: 'error', error });
        }
      );
    }, opts.delayMs);
  };

  return {
    schedule,
    dispose: () => {
      disposed = true;
      cancel();
    }
  };
};
