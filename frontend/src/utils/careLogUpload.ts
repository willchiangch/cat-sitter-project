import { completeMediaUpload, getUploadTarget, requestMediaUpload } from '../api/fulfillApi';
import type { MediaCreated } from '../api/fulfillApi';
import { ApiRequestError, getApiError, payloadString } from '../api/apiError';
import { newIdempotencyKey } from '../api/idempotency';
import {
  CARE_LOG_PHOTO_OPTIONS,
  ImageDecodeError,
  ImageTooLargeError,
  reencodeImage,
  sha256Hex
} from './imageReencode';

/**
 * 照護日誌媒體直傳 GCS（SD-FULFILL §4.4、§9.3）：
 * 1. 照片：長邊 2048 px、WebP 品質 0.9 → 0.5，第一個 < 1 MB 者採用（重新編碼同時移除 EXIF，SD-FRONTEND-SPEC §2.7）；
 *    瀏覽器無法編碼 WebP 時改 JPEG 同品質階梯；F16 宣告的 contentType 一律取實際 blob.type。
 *    影片：<video> 讀時長 half-up，需 15–30 秒、MP4 / MOV、< 50 MB。
 * 2. 以重新編碼後的 bytes 計算 SHA-256，產生 Idempotency-Key。
 * 3. F16 → F17（alreadyCompleted 略過 PUT）→ PUT uploadUrl（帶回應指定標頭；412 視為已上傳）→ F18。
 * 4. 重試規則見 SD-FULFILL §9.3 表：PUT 失敗 / F18 OBJECT_MISSING → 同槽位重做 F17 + PUT + F18；
 *    F18 ORDER_BUSY / 5xx → 同槽位直接重打 F18；確定性錯誤 → 丟棄槽位，重選檔案換新 key 從 F16 開始。
 */

export class MediaRejectError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MediaRejectError';
  }
}

/** PUT 到 GCS 失敗（網路 / GCS）：保留槽位，重試時同槽位重做 F17 + PUT + F18 */
export class UploadPutError extends Error {
  constructor() {
    super('上傳失敗，請稍候重試');
    this.name = 'UploadPutError';
  }
}

export interface PreparedMedia {
  blob: Blob;
  mediaType: 'IMAGE' | 'VIDEO';
  contentType: string;
  durationSeconds?: number;
  sha256: string;
}

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const VIDEO_TYPES = ['video/mp4', 'video/quicktime'];
const VIDEO_MAX_BYTES = 50 * 1024 * 1024;
const UNSUPPORTED = '照片僅支援 JPG、PNG、WebP；影片僅支援 MP4、MOV';

const readVideoDuration = (file: Blob): Promise<number> =>
  new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.onloadedmetadata = () => {
      URL.revokeObjectURL(url);
      resolve(video.duration);
    };
    video.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new MediaRejectError(UNSUPPORTED));
    };
    video.src = url;
  });

/** half-up 取整數秒（與後端 MediaInspector 同規則） */
const halfUp = (seconds: number) => Math.floor(seconds + 0.5);

export const prepareCareLogMedia = async (
  file: File,
  quota: { imageLimit: number; imageUsed: number; videoLimit: number; videoUsed: number }
): Promise<PreparedMedia> => {
  if (IMAGE_TYPES.includes(file.type)) {
    if (quota.imageUsed >= quota.imageLimit) throw new MediaRejectError('此行程照片額度已用完');
    let blob: Blob;
    try {
      blob = await reencodeImage(file, CARE_LOG_PHOTO_OPTIONS);
    } catch (e) {
      if (e instanceof ImageTooLargeError) throw new MediaRejectError('照片需小於 1MB');
      if (e instanceof ImageDecodeError) throw new MediaRejectError(UNSUPPORTED);
      throw e;
    }
    return { blob, mediaType: 'IMAGE', contentType: blob.type, sha256: await sha256Hex(blob) };
  }
  if (VIDEO_TYPES.includes(file.type)) {
    if (quota.videoUsed >= quota.videoLimit) throw new MediaRejectError('此行程影片額度已用完');
    if (file.size >= VIDEO_MAX_BYTES) throw new MediaRejectError('影片需小於 50MB');
    const seconds = halfUp(await readVideoDuration(file));
    if (seconds < 15 || seconds > 30) throw new MediaRejectError('影片長度需為 15–30 秒');
    return {
      blob: file,
      mediaType: 'VIDEO',
      contentType: file.type,
      durationSeconds: seconds,
      sha256: await sha256Hex(file)
    };
  }
  throw new MediaRejectError(UNSUPPORTED);
};

/** 一個檔案的上傳狀態（跨重試保留） */
export interface UploadSession {
  key: string;
  uploadId?: string;
  /** 物件是否已確定在 GCS（PUT 成功或 412）：重試時直接 F18 */
  objectUploaded: boolean;
}

export const newUploadSession = (): UploadSession => ({
  key: newIdempotencyKey(),
  objectUploaded: false
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const putToGcs = async (url: string, headers: Record<string, string>, blob: Blob) => {
  let res: Response;
  try {
    res = await fetch(url, { method: 'PUT', headers, body: blob });
  } catch {
    throw new UploadPutError();
  }
  // 412 = x-goog-if-generation-match=0 前置條件不符 → 物件已存在，視為已上傳
  if (!res.ok && res.status !== 412) throw new UploadPutError();
};

const isRetryableComplete = (e: unknown) => {
  const err = getApiError(e);
  return (
    err.code === 'ORDER_BUSY' ||
    err.code === 'NETWORK_ERROR' ||
    (err.status !== null && err.status >= 500)
  );
};

const isObjectMissing = (e: unknown) => {
  const err = getApiError(e);
  return err.code === 'VALIDATION_FAILED' && payloadString(err, 'reason') === 'OBJECT_MISSING';
};

/**
 * 執行（或接續）一次上傳。`session` 會被就地更新（uploadId / objectUploaded），呼叫端保留它供「重試」使用。
 * 確定性錯誤直接拋出（呼叫端應丟棄 session）；UploadPutError / 可重試錯誤拋出時 session 仍可續用。
 */
export const runCareLogUpload = async (
  orderId: string,
  visitId: string,
  media: PreparedMedia,
  caption: string,
  session: UploadSession
): Promise<MediaCreated> => {
  if (!session.uploadId) {
    const slot = await requestMediaUpload(
      orderId,
      visitId,
      {
        file: {
          mediaType: media.mediaType,
          contentType: media.contentType,
          sizeBytes: media.blob.size,
          sha256: media.sha256,
          ...(media.durationSeconds !== undefined ? { durationSeconds: media.durationSeconds } : {})
        },
        ...(caption ? { caption } : {})
      },
      session.key
    );
    session.uploadId = slot.uploadId;
  }
  const uploadId = session.uploadId;

  let objectMissingRetried = false;
  for (;;) {
    if (!session.objectUploaded) {
      const target = await getUploadTarget(orderId, visitId, uploadId);
      if (!target.alreadyCompleted) {
        if (!target.uploadUrl) throw new UploadPutError();
        await putToGcs(target.uploadUrl, target.headers ?? {}, media.blob);
      }
      session.objectUploaded = true;
    }
    // F18：可重試錯誤自動重打最多 2 次（物件已在 GCS，不重傳）
    let attempts = 0;
    for (;;) {
      try {
        return await completeMediaUpload(orderId, visitId, uploadId);
      } catch (e) {
        if (isObjectMissing(e) && !objectMissingRetried) {
          objectMissingRetried = true;
          session.objectUploaded = false;
          break; // 外層重做 F17 + PUT + F18
        }
        if (isRetryableComplete(e) && attempts < 2) {
          attempts += 1;
          await sleep((getApiError(e).retryAfterSec ?? 1) * 1000);
          continue;
        }
        throw e instanceof Error ? e : new ApiRequestError(getApiError(e));
      }
    }
  }
};

/** 錯誤是否可沿用同一槽位重試（否則重選檔案換新 key） */
export const isSessionReusable = (e: unknown): boolean =>
  e instanceof UploadPutError || isRetryableComplete(e) || isObjectMissing(e);
