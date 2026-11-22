import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CARE_LOG_PHOTO_OPTIONS,
  ImageDecodeError,
  ImageTooLargeError,
  extensionOf,
  reencodeImage,
  reencodeImageFile,
  sha256Hex
} from './imageReencode';

/**
 * SD-FRONTEND-SPEC §2.7（NFR-003 SEC-FILE-5.3）。jsdom 沒有真的 canvas 編碼，以 mock 驗證：
 * - 解碼一律 createImageBitmap(file, { imageOrientation: 'from-image' })（方向由瀏覽器套用）
 * - 輸出永遠是 canvas.toBlob 重繪的新 bytes，原檔（含 EXIF APP1 / GPS）不會被原樣交回
 * 真實瀏覽器「輸出不含 Exif 區段」需以 E2E / 手動驗證（canvas 本身不寫 EXIF）。
 */

// 最小 JPEG：SOI + APP1("Exif\0\0" + "GPS") + EOI
const JPEG_WITH_EXIF = new Uint8Array([
  0xff, 0xd8, 0xff, 0xe1, 0x00, 0x0d, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00, 0x47, 0x50, 0x53, 0x00,
  0x00, 0xff, 0xd9
]);
// canvas 輸出：SOI + APP0(JFIF) + EOI，沒有 APP1
const JPEG_NO_EXIF = new Uint8Array([
  0xff, 0xd8, 0xff, 0xe0, 0x00, 0x07, 0x4a, 0x46, 0x49, 0x46, 0x00, 0xff, 0xd9
]);

const hasExifApp1 = (bytes: Uint8Array) => {
  for (let i = 0; i + 9 < bytes.length; i++) {
    if (
      bytes[i] === 0xff &&
      bytes[i + 1] === 0xe1 &&
      String.fromCharCode(...bytes.slice(i + 4, i + 8)) === 'Exif'
    )
      return true;
  }
  return false;
};

interface CanvasMock {
  drawImage: ReturnType<typeof vi.fn>;
  toBlobCalls: { type: string; quality: number | undefined }[];
  sizes: number[];
  canvas?: HTMLCanvasElement;
}

let mock: CanvasMock;

/** 依序回傳 sizes[i] 大小的 blob（最後一個重複使用）；`noWebp` 模擬 Safari：要 WebP 時回 PNG */
const setupCanvas = (
  sizes: number[] = [JPEG_NO_EXIF.length],
  noContext = false,
  noWebp = false
) => {
  mock = { drawImage: vi.fn(), toBlobCalls: [], sizes };
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function (
    this: HTMLCanvasElement
  ) {
    mock.canvas = this;
    return noContext
      ? null
      : ({ drawImage: mock.drawImage } as unknown as CanvasRenderingContext2D);
  } as HTMLCanvasElement['getContext']);
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (
    cb: BlobCallback,
    type?: string,
    quality?: number
  ) {
    const i = mock.toBlobCalls.length;
    mock.toBlobCalls.push({ type: type ?? 'image/png', quality });
    const size = mock.sizes[Math.min(i, mock.sizes.length - 1)];
    const bytes = size === JPEG_NO_EXIF.length ? JPEG_NO_EXIF : new Uint8Array(size).fill(0x20);
    const actual = noWebp && type === 'image/webp' ? 'image/png' : (type ?? 'image/png');
    cb(new Blob([bytes], { type: actual }));
  });
};

const bitmap = (width: number, height: number) => {
  const close = vi.fn();
  return { width, height, close } as unknown as ImageBitmap & { close: typeof close };
};

beforeEach(() => {
  setupCanvas();
});

describe('reencodeImage', () => {
  it('以 imageOrientation: from-image 解碼，輸出為 canvas 重繪的新檔（不含原檔 EXIF APP1）', async () => {
    const input = new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' });
    expect(hasExifApp1(JPEG_WITH_EXIF)).toBe(true);
    const cib = vi.fn().mockResolvedValue(bitmap(4000, 3000));
    vi.stubGlobal('createImageBitmap', cib);

    const out = await reencodeImage(input);

    expect(cib).toHaveBeenCalledWith(input, { imageOrientation: 'from-image' });
    expect(out).not.toBe(input);
    const bytes = new Uint8Array(await out.arrayBuffer());
    expect(hasExifApp1(bytes)).toBe(false);
    expect(mock.toBlobCalls).toEqual([{ type: 'image/jpeg', quality: 0.92 }]);
  });

  it('方向已套用：createImageBitmap 回傳轉正後的寬高（直拍 3000×4000），canvas 依此尺寸繪製', async () => {
    const bm = bitmap(3000, 4000);
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bm));
    await reencodeImage(new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }));
    expect(mock.canvas!.width).toBe(3000);
    expect(mock.canvas!.height).toBe(4000);
    expect(mock.drawImage).toHaveBeenCalledWith(bm, 0, 0, 3000, 4000);
    expect(bm.close).toHaveBeenCalled();
  });

  it('日誌照片規格：長邊縮到 2048、WebP、品質 0.9 起依序嘗試，第一個 < 1 MB 者採用', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(3000, 4000)));
    setupCanvas([2_000_000, 1_048_576, 900_000]);
    const out = await reencodeImage(
      new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }),
      CARE_LOG_PHOTO_OPTIONS
    );
    expect(mock.canvas!.height).toBe(2048);
    expect(mock.canvas!.width).toBe(1536);
    expect(mock.toBlobCalls).toEqual([
      { type: 'image/webp', quality: 0.9 },
      { type: 'image/webp', quality: 0.8 },
      { type: 'image/webp', quality: 0.7 }
    ]);
    expect(out.size).toBe(900_000);
  });

  it('瀏覽器無法編碼 WebP（回 PNG）→ 改 JPEG，從同一品質起走完整階梯', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(3000, 4000)));
    // 第 1 次（WebP→PNG）不論大小都不採用；之後 JPEG 0.9、0.8 太大，0.7 採用
    setupCanvas([5_000_000, 2_000_000, 1_048_576, 900_000], false, true);
    const out = await reencodeImage(
      new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }),
      CARE_LOG_PHOTO_OPTIONS
    );
    expect(mock.toBlobCalls).toEqual([
      { type: 'image/webp', quality: 0.9 },
      { type: 'image/jpeg', quality: 0.9 },
      { type: 'image/jpeg', quality: 0.8 },
      { type: 'image/jpeg', quality: 0.7 }
    ]);
    expect(out.type).toBe('image/jpeg');
    expect(out.size).toBe(900_000);
  });

  it('WebP 回 PNG 即使已小於上限也不採用（避免宣告 WebP 實為 PNG）', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(100, 100)));
    setupCanvas([500], false, true);
    const out = await reencodeImage(
      new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }),
      CARE_LOG_PHOTO_OPTIONS
    );
    expect(out.type).toBe('image/jpeg');
    expect(mock.toBlobCalls.map((c) => c.type)).toEqual(['image/webp', 'image/jpeg']);
  });

  it('WebP 改 JPEG 後全部品質仍 ≥ 上限 → ImageTooLargeError（JPEG 走滿 5 階）', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(100, 100)));
    setupCanvas([2_000_000], false, true);
    await expect(
      reencodeImage(new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }), CARE_LOG_PHOTO_OPTIONS)
    ).rejects.toBeInstanceOf(ImageTooLargeError);
    expect(mock.toBlobCalls).toHaveLength(6);
    expect(mock.toBlobCalls.slice(1).every((c) => c.type === 'image/jpeg')).toBe(true);
  });

  it('小圖不放大', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(800, 600)));
    await reencodeImage(new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }), { maxLongEdge: 2048 });
    expect([mock.canvas!.width, mock.canvas!.height]).toEqual([800, 600]);
  });

  it('所有品質都 ≥ 上限 → ImageTooLargeError', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(100, 100)));
    setupCanvas([2_000_000]);
    await expect(
      reencodeImage(new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }), CARE_LOG_PHOTO_OPTIONS)
    ).rejects.toBeInstanceOf(ImageTooLargeError);
    expect(mock.toBlobCalls).toHaveLength(5);
  });

  it('PNG 維持 PNG、無損（不帶品質）；WebP 維持 WebP', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(10, 10)));
    await reencodeImage(new Blob([new Uint8Array(4)], { type: 'image/png' }));
    await reencodeImage(new Blob([new Uint8Array(4)], { type: 'image/webp' }));
    expect(mock.toBlobCalls).toEqual([
      { type: 'image/png', quality: undefined },
      { type: 'image/webp', quality: 0.92 }
    ]);
  });

  it('寬高為 0 或拿不到 2d context → ImageDecodeError', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(0, 0)));
    await expect(
      reencodeImage(new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }))
    ).rejects.toBeInstanceOf(ImageDecodeError);
    setupCanvas(undefined, true);
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(10, 10)));
    await expect(
      reencodeImage(new Blob([JPEG_WITH_EXIF], { type: 'image/jpeg' }))
    ).rejects.toBeInstanceOf(ImageDecodeError);
  });
});

describe('reencodeImageFile', () => {
  it('JPEG 重新編碼為新 File，副檔名依輸出格式', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(10, 10)));
    const file = new File([JPEG_WITH_EXIF], 'IMG_0001.JPEG', { type: 'image/jpeg' });
    const out = await reencodeImageFile(file);
    expect(out).not.toBe(file);
    expect(out.name).toBe('IMG_0001.jpg');
    expect(out.type).toBe('image/jpeg');
    expect(hasExifApp1(new Uint8Array(await out.arrayBuffer()))).toBe(false);
  });

  it('WebP 原檔在無法編碼 WebP 的瀏覽器 → 輸出 JPEG，File 型別與副檔名跟著實際格式', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bitmap(10, 10)));
    setupCanvas(undefined, false, true);
    const out = await reencodeImageFile(
      new File([new Uint8Array(4)], 'cat.webp', { type: 'image/webp' })
    );
    expect(out.type).toBe('image/jpeg');
    expect(out.name).toBe('cat.jpg');
    expect(mock.toBlobCalls).toEqual([
      { type: 'image/webp', quality: 0.92 },
      { type: 'image/jpeg', quality: 0.92 }
    ]);
  });

  it('非 JPEG / PNG / WebP（影片、HEIC）原樣交回，不經 canvas', async () => {
    const cib = vi.fn();
    vi.stubGlobal('createImageBitmap', cib);
    const video = new File([new Uint8Array(4)], 'a.mp4', { type: 'video/mp4' });
    const heic = new File([new Uint8Array(4)], 'a.heic', { type: 'image/heic' });
    expect(await reencodeImageFile(video)).toBe(video);
    expect(await reencodeImageFile(heic)).toBe(heic);
    expect(cib).not.toHaveBeenCalled();
  });
});

describe('helpers', () => {
  it('extensionOf', () => {
    expect(extensionOf('image/png')).toBe('png');
    expect(extensionOf('image/webp')).toBe('webp');
    expect(extensionOf('image/jpeg')).toBe('jpg');
  });

  it('sha256Hex 以 bytes 計算', async () => {
    expect(await sha256Hex(new Blob(['abc']))).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    );
  });
});
