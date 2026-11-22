import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createLatestDebounced, type LatestState } from './latestDebounced';

// SD-ORDER §9.2：變更金額試算防抖 400 ms、只採最後一次請求的回應
interface Deferred {
  input: number;
  signal: AbortSignal;
  resolve: (v: string) => void;
  reject: (e: unknown) => void;
}

const setup = () => {
  const calls: Deferred[] = [];
  const states: LatestState<string>[] = [];
  const d = createLatestDebounced<number, string>({
    delayMs: 400,
    fetch: (input, signal) =>
      new Promise<string>((resolve, reject) => calls.push({ input, signal, resolve, reject })),
    onState: (s) => states.push(s)
  });
  return { d, calls, states, last: () => states[states.length - 1] };
};

const flush = () => Promise.resolve().then(() => Promise.resolve());

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('createLatestDebounced', () => {
  it('排程後立即 loading，400 ms 後才送出', () => {
    const { d, calls, last } = setup();
    d.schedule(1);
    expect(last()).toEqual({ status: 'loading' });
    vi.advanceTimersByTime(399);
    expect(calls).toHaveLength(0);
    vi.advanceTimersByTime(1);
    expect(calls).toHaveLength(1);
    expect(calls[0].input).toBe(1);
  });

  it('防抖期間連續變動只送最後一次', () => {
    const { d, calls } = setup();
    d.schedule(1);
    vi.advanceTimersByTime(200);
    d.schedule(2);
    vi.advanceTimersByTime(200);
    d.schedule(3);
    vi.advanceTimersByTime(400);
    expect(calls.map((c) => c.input)).toEqual([3]);
  });

  it('較早的回應晚到被丟棄，且較早的請求被 abort', async () => {
    const { d, calls, last } = setup();
    d.schedule(1);
    vi.advanceTimersByTime(400);
    d.schedule(2);
    expect(calls[0].signal.aborted).toBe(true);
    vi.advanceTimersByTime(400);
    calls[1].resolve('second');
    await flush();
    expect(last()).toEqual({ status: 'success', data: 'second' });
    calls[0].resolve('first');
    await flush();
    expect(last()).toEqual({ status: 'success', data: 'second' });
  });

  it('較早的請求失敗晚到也被丟棄', async () => {
    const { d, calls, last } = setup();
    d.schedule(1);
    vi.advanceTimersByTime(400);
    d.schedule(2);
    vi.advanceTimersByTime(400);
    calls[1].resolve('ok');
    await flush();
    calls[0].reject(new Error('stale'));
    await flush();
    expect(last()).toEqual({ status: 'success', data: 'ok' });
  });

  it('最新請求失敗回報 error', async () => {
    const { d, calls, last } = setup();
    d.schedule(1);
    vi.advanceTimersByTime(400);
    const err = new Error('boom');
    calls[0].reject(err);
    await flush();
    expect(last()).toEqual({ status: 'error', error: err });
  });

  it('schedule(null) 取消計時與進行中請求並回 idle', async () => {
    const { d, calls, last } = setup();
    d.schedule(1);
    vi.advanceTimersByTime(400);
    d.schedule(null);
    expect(last()).toEqual({ status: 'idle' });
    expect(calls[0].signal.aborted).toBe(true);
    calls[0].resolve('late');
    await flush();
    expect(last()).toEqual({ status: 'idle' });
    d.schedule(2);
    d.schedule(null);
    vi.advanceTimersByTime(400);
    expect(calls).toHaveLength(1);
  });

  it('dispose 後不再回報、不再送出', async () => {
    const { d, calls, states } = setup();
    d.schedule(1);
    vi.advanceTimersByTime(400);
    d.dispose();
    const n = states.length;
    calls[0].resolve('late');
    await flush();
    d.schedule(2);
    vi.advanceTimersByTime(400);
    expect(states).toHaveLength(n);
    expect(calls).toHaveLength(1);
  });
});
