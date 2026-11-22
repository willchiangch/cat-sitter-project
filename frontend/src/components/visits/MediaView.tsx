import React, { useState } from 'react';
import type { MediaItem } from '../../api/fulfillApi';

/**
 * 日誌媒體：只把後端 F13 給的 V4 簽名 GET 網址放進 img / video（SD-FRONTEND-SPEC §2.6 SEC-WEB-6）。
 * `purged` → 「檔案已逾期移除」；載入失敗（網址過期）→ 通知呼叫端重新呼叫 F13 取新網址（只重試一次）。
 */
const MediaView: React.FC<{ media: MediaItem; onUrlExpired: () => void }> = ({
  media,
  onUrlExpired
}) => {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  if (media.purged || !media.url) {
    return (
      <div className="surface-block muted" data-testid="purged-media-placeholder">
        檔案已逾期移除
      </div>
    );
  }
  const onError = () => {
    if (failedUrl === media.url) return;
    setFailedUrl(media.url ?? null);
    onUrlExpired();
  };
  if (failedUrl === media.url) {
    return <div className="surface-block muted">媒體暫時無法載入</div>;
  }
  return media.mediaType === 'VIDEO' ? (
    <video
      src={media.url}
      controls
      preload="metadata"
      onError={onError}
      style={{ maxWidth: '100%', borderRadius: 'var(--radius-sm)' }}
    />
  ) : (
    <img
      src={media.url}
      alt={media.caption ?? '照護照片'}
      onError={onError}
      style={{ maxWidth: '100%', borderRadius: 'var(--radius-sm)' }}
    />
  );
};

export default MediaView;
