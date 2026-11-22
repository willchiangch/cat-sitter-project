import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiRequestError, type ApiError } from '../api/apiError';

vi.mock('./imageReencode', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./imageReencode')>();
  return { ...actual, reencodeImage: vi.fn() };
});

import { ImageDecodeError, ImageTooLargeError, reencodeImage } from './imageReencode';
import {
  MediaRejectError,
  UploadPutError,
  isSessionReusable,
  newUploadSession,
  prepareCareLogMedia
} from './careLogUpload';

// SD-FULFILL §9.3：照片 / 影片前置檢查與重試分類（純邏輯部分）
const QUOTA = { imageLimit: 5, imageUsed: 0, videoLimit: 1, videoUsed: 0 };
const reencode = vi.mocked(reencodeImage);

const apiErr = (patch: Partial<ApiError>) =>
  new ApiRequestError({
    status: 400,
    code: 'UNKNOWN',
    serverMessage: null,
    payload: {},
    retryAfterSec: null,
    traceId: null,
    ...patch
  });

/** 讓 <video> 讀到指定時長 */
const stubVideoDuration = (duration: number | 'error') => {
  vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:x', revokeObjectURL: () => {} });
  const realCreate = document.createElement.bind(document);
  vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
    if (tag !== 'video') return realCreate(tag);
    const v = {
      preload: '',
      duration: 0,
      onloadedmetadata: null as null | (() => void),
      onerror: null as null | (() => void),
      set src(_url: string) {
        queueMicrotask(() => {
          if (duration === 'error') v.onerror?.();
          else {
            v.duration = duration;
            v.onloadedmetadata?.();
          }
        });
      }
    };
    return v as unknown as HTMLVideoElement;
  }) as typeof document.createElement);
};

beforeEach(() => reencode.mockReset());

describe('prepareCareLogMedia：照片', () => {
  it('重新編碼後以新 bytes 計算 sha256', async () => {
    const out = new Blob(['abc'], { type: 'image/webp' });
    reencode.mockResolvedValue(out);
    const r = await prepareCareLogMedia(new File(['x'], 'a.jpg', { type: 'image/jpeg' }), QUOTA);
    expect(r).toEqual({
      blob: out,
      mediaType: 'IMAGE',
      contentType: 'image/webp',
      sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    });
  });

  it('額度用完不編碼', async () => {
    await expect(
      prepareCareLogMedia(new File(['x'], 'a.png', { type: 'image/png' }), {
        ...QUOTA,
        imageUsed: 5
      })
    ).rejects.toThrow('此行程照片額度已用完');
    expect(reencode).not.toHaveBeenCalled();
  });

  it('過大 / 無法解碼轉成畫面文案', async () => {
    const f = new File(['x'], 'a.jpg', { type: 'image/jpeg' });
    reencode.mockRejectedValueOnce(new ImageTooLargeError());
    await expect(prepareCareLogMedia(f, QUOTA)).rejects.toThrow('照片需小於 1MB');
    reencode.mockRejectedValueOnce(new ImageDecodeError());
    await expect(prepareCareLogMedia(f, QUOTA)).rejects.toThrow(
      '照片僅支援 JPG、PNG、WebP；影片僅支援 MP4、MOV'
    );
  });
});

describe('prepareCareLogMedia：影片', () => {
  const mp4 = (size = 10) => new File([new Uint8Array(size)], 'a.mp4', { type: 'video/mp4' });

  it('時長 half-up：14.5 秒 → 15 通過、30.4 → 30 通過', async () => {
    stubVideoDuration(14.5);
    expect((await prepareCareLogMedia(mp4(), QUOTA)).durationSeconds).toBe(15);
    stubVideoDuration(30.4);
    const r = await prepareCareLogMedia(mp4(), QUOTA);
    expect(r).toMatchObject({ mediaType: 'VIDEO', contentType: 'video/mp4', durationSeconds: 30 });
  });

  it('時長 14.4 → 14、30.5 → 31 拒絕', async () => {
    stubVideoDuration(14.4);
    await expect(prepareCareLogMedia(mp4(), QUOTA)).rejects.toThrow('影片長度需為 15–30 秒');
    stubVideoDuration(30.5);
    await expect(prepareCareLogMedia(mp4(), QUOTA)).rejects.toThrow('影片長度需為 15–30 秒');
  });

  it('無法讀取 metadata → 格式不支援', async () => {
    stubVideoDuration('error');
    await expect(prepareCareLogMedia(mp4(), QUOTA)).rejects.toBeInstanceOf(MediaRejectError);
  });

  it('額度用完、≥ 50 MB 先擋（不讀時長）', async () => {
    await expect(prepareCareLogMedia(mp4(), { ...QUOTA, videoUsed: 1 })).rejects.toThrow(
      '此行程影片額度已用完'
    );
    const big = mp4();
    Object.defineProperty(big, 'size', { value: 50 * 1024 * 1024 });
    await expect(prepareCareLogMedia(big, QUOTA)).rejects.toThrow('影片需小於 50MB');
  });

  it('其他型別拒絕', async () => {
    await expect(
      prepareCareLogMedia(new File(['x'], 'a.heic', { type: 'image/heic' }), QUOTA)
    ).rejects.toThrow('照片僅支援 JPG、PNG、WebP；影片僅支援 MP4、MOV');
  });
});

describe('isSessionReusable（SD-FULFILL §9.3 重試表）', () => {
  it.each([
    ['PUT 失敗', new UploadPutError(), true],
    ['F18 ORDER_BUSY', apiErr({ status: 409, code: 'ORDER_BUSY' }), true],
    ['5xx', apiErr({ status: 503, code: 'HTTP_503' }), true],
    ['網路錯誤', apiErr({ status: null, code: 'NETWORK_ERROR' }), true],
    [
      'OBJECT_MISSING',
      apiErr({ code: 'VALIDATION_FAILED', payload: { reason: 'OBJECT_MISSING' } }),
      true
    ],
    ['其他 VALIDATION_FAILED', apiErr({ code: 'VALIDATION_FAILED', payload: {} }), false],
    ['403', apiErr({ status: 403, code: 'FORBIDDEN' }), false]
  ])('%s → %s', (_l, e, expected) => {
    expect(isSessionReusable(e)).toBe(expected);
  });

  it('newUploadSession 每次產生新 key、尚未上傳', () => {
    const a = newUploadSession();
    const b = newUploadSession();
    expect(a.key).not.toBe(b.key);
    expect(a.objectUploaded).toBe(false);
    expect(a.uploadId).toBeUndefined();
  });
});
