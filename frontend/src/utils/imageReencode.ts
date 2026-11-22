/**
 * 照片上傳前以 canvas 重新編碼（SD-FRONTEND-SPEC §2.7，NFR-003 SEC-FILE-5.3）：重繪輸出新檔即移除 EXIF（含 GPS）。
 * 先以 `createImageBitmap(file, { imageOrientation: 'from-image' })` 套用 EXIF 方向（否則直拍照片重繪後會轉 90 度）。
 *
 * - 模組未指定格式：維持原格式（JPEG / WebP 品質 0.92，PNG 無損重繪，避免轉帳截圖文字被有損壓縮糊掉）。
 * - 模組指定（SD-FULFILL §9.3 日誌照片）：長邊 2048 px、WebP、品質 0.9 → 0.5，第一個小於上限者採用。
 * 需要宣告雜湊或大小的流程，一律以「重新編碼後」的 bytes 計算（`sha256Hex`）。
 */

export class ImageTooLargeError extends Error {
  constructor() {
    super('IMAGE_TOO_LARGE');
    this.name = 'ImageTooLargeError';
  }
}

export class ImageDecodeError extends Error {
  constructor() {
    super('IMAGE_DECODE_FAILED');
    this.name = 'ImageDecodeError';
  }
}

export interface ReencodeOptions {
  /** 長邊上限（px）；原圖較小不放大。未指定 = 不縮放 */
  maxLongEdge?: number;
  /** 輸出格式；未指定 = 依原檔（PNG → PNG、WebP → WebP、其他 → JPEG） */
  mimeType?: 'image/jpeg' | 'image/png' | 'image/webp';
  /** 依序嘗試的品質；未指定 = [0.92]（PNG 忽略） */
  qualities?: number[];
  /** 位元組上限：所有品質都 ≥ 上限 → ImageTooLargeError */
  maxBytes?: number;
}

const decode = async (file: Blob): Promise<ImageBitmap | HTMLImageElement> => {
  if (typeof createImageBitmap === 'function') {
    try {
      return await createImageBitmap(file, { imageOrientation: 'from-image' });
    } catch {
      // 部分瀏覽器不支援 options：退回 <img>（現代瀏覽器 <img> 預設依 EXIF 方向繪製）
    }
  }
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    await img.decode();
    return img;
  } catch {
    throw new ImageDecodeError();
  } finally {
    URL.revokeObjectURL(url);
  }
};

const toBlob = (canvas: HTMLCanvasElement, type: string, quality?: number) =>
  new Promise<Blob>((resolve, reject) =>
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new ImageDecodeError())),
      type,
      type === 'image/png' ? undefined : quality
    )
  );

const pickOutputType = (inputType: string): 'image/jpeg' | 'image/png' | 'image/webp' => {
  if (inputType === 'image/png') return 'image/png';
  if (inputType === 'image/webp') return 'image/webp';
  return 'image/jpeg';
};

export const extensionOf = (mime: string): string =>
  mime === 'image/png' ? 'png' : mime === 'image/webp' ? 'webp' : 'jpg';

export const reencodeImage = async (file: Blob, options: ReencodeOptions = {}): Promise<Blob> => {
  const source = await decode(file);
  const w = 'naturalWidth' in source ? source.naturalWidth : source.width;
  const h = 'naturalHeight' in source ? source.naturalHeight : source.height;
  if (!w || !h) throw new ImageDecodeError();
  const scale = options.maxLongEdge ? Math.min(1, options.maxLongEdge / Math.max(w, h)) : 1;
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(w * scale));
  canvas.height = Math.max(1, Math.round(h * scale));
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new ImageDecodeError();
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
  if ('close' in source) source.close();

  const type = options.mimeType ?? pickOutputType(file.type);
  const qualities = type === 'image/png' ? [1] : (options.qualities ?? [0.92]);
  for (const q of qualities) {
    const blob = await toBlob(canvas, type, q);
    if (options.maxBytes === undefined || blob.size < options.maxBytes) return blob;
  }
  throw new ImageTooLargeError();
};

/** SD-FULFILL §9.3 日誌照片規格 */
export const CARE_LOG_PHOTO_OPTIONS: ReencodeOptions = {
  maxLongEdge: 2048,
  mimeType: 'image/webp',
  qualities: [0.9, 0.8, 0.7, 0.6, 0.5],
  maxBytes: 1_048_576
};

export const sha256Hex = async (blob: Blob): Promise<string> => {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
};

const REENCODABLE = ['image/jpeg', 'image/png', 'image/webp'];

/**
 * 一般照片上傳（KYC、頭像、毛孩照片、照護記事媒體）：JPEG / PNG / WebP 重新編碼為同格式新檔（移除 EXIF）；
 * 其他型別（影片、HEIC 等瀏覽器無法解碼者）原樣交回，由後端依格式規則回錯。
 */
export const reencodeImageFile = async (file: File): Promise<File> => {
  if (!REENCODABLE.includes(file.type)) return file;
  const blob = await reencodeImage(file);
  const base = file.name.replace(/\.[^.]+$/, '') || 'image';
  return new File([blob], `${base}.${extensionOf(blob.type)}`, {
    type: blob.type,
    lastModified: file.lastModified
  });
};
