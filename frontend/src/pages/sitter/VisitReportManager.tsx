import React, { useEffect, useState, useRef } from 'react';
import {
  useVisitReportQuery,
  useSaveDraftMutation,
  useUploadReportMediaMutation,
  useDeleteReportMediaMutation,
  useSubmitReportMutation,
  useStartVisitMutation,
  useEndVisitMutation
} from '../../hooks/useVisitReport';

interface VisitReportManagerProps {
  visitId: string;
}

const VisitReportManager: React.FC<VisitReportManagerProps> = ({ visitId }) => {
  const { data: report, isLoading } = useVisitReportQuery(visitId);

  const saveMutation = useSaveDraftMutation(visitId);
  const uploadMutation = useUploadReportMediaMutation(visitId);
  const deleteMutation = useDeleteReportMediaMutation(visitId);
  const submitMutation = useSubmitReportMutation(visitId);
  const startMutation = useStartVisitMutation(visitId);
  const endMutation = useEndVisitMutation(visitId);

  const [content, setContent] = useState('');
  const [mediaFile, setMediaFile] = useState<File | null>(null);
  const [mediaCaption, setMediaCaption] = useState('');
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // 冪等性 Key Refs
  const saveKeyRef = useRef<string | null>(null);
  const uploadKeyRef = useRef<string | null>(null);
  const deleteKeyRef = useRef<Record<string, string>>({});
  const submitKeyRef = useRef<string | null>(null);
  const startKeyRef = useRef<string | null>(null);
  const endKeyRef = useRef<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (report) {
      setContent(report.content || '');
    }
  }, [report]);

  if (isLoading) {
    return (
      <div
        style={{ padding: '2rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
      >
        正在載入照護日誌...
      </div>
    );
  }

  const isEditable = (report?.isEditable ?? true) && report?.visitStatus === 'IN_PROGRESS';
  const isSubmitted = report?.status === 'SUBMITTED';

  const getBadgeStyleAndText = () => {
    const visitStatus = report?.visitStatus || 'PENDING';
    if (visitStatus === 'PENDING') {
      return {
        bg: 'var(--color-surface-low)',
        color: 'var(--color-on-surface-variant)',
        text: '待執行'
      };
    }
    if (visitStatus === 'IN_PROGRESS') {
      return {
        bg: '#fef3c7',
        color: '#d97706',
        text: '執行中'
      };
    }
    if (isSubmitted) {
      return {
        bg: 'var(--color-primary-container)',
        color: 'var(--color-primary)',
        text: '已完工 (已送出)'
      };
    }
    if (report && !report.isEditable) {
      return {
        bg: 'var(--color-error-container)',
        color: 'var(--color-error)',
        text: '已逾期 (唯讀)'
      };
    }
    return {
      bg: '#ffedd5',
      color: '#ea580c',
      text: '待送出日誌'
    };
  };

  const badge = getBadgeStyleAndText();

  // 輔助函式：產生 UUID
  const getUuid = () => {
    return crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).substring(2);
  };

  const handleStartVisit = async () => {
    if (!startKeyRef.current) {
      startKeyRef.current = getUuid();
    }
    setUploadError(null);
    setSuccessMsg(null);
    try {
      await startMutation.mutateAsync(startKeyRef.current);
      setSuccessMsg('已成功 Check-in，照護服務開始！');
      startKeyRef.current = null;
    } catch (err: any) {
      const errMsg = err.response?.data?.message || 'Check-in 失敗';
      setUploadError(errMsg);
    }
  };

  const handleEndVisit = async () => {
    if (!window.confirm('確定要結束本次照護服務嗎？結束後行程將關閉，且記錄完工時間。')) return;
    if (!endKeyRef.current) {
      endKeyRef.current = getUuid();
    }
    setUploadError(null);
    setSuccessMsg(null);
    try {
      await endMutation.mutateAsync(endKeyRef.current);
      setSuccessMsg('已成功 Check-out，行程已完工！請填寫文字日誌並送出。');
      endKeyRef.current = null;
    } catch (err: any) {
      const errMsg = err.response?.data?.message || 'Check-out 失敗';
      setUploadError(errMsg);
    }
  };

  const handleSaveDraft = async () => {
    if (!isEditable) return;
    if (!saveKeyRef.current) {
      saveKeyRef.current = getUuid();
    }

    setUploadError(null);
    setSuccessMsg(null);

    try {
      await saveMutation.mutateAsync({
        content,
        version: report?.version ?? 0,
        idempotencyKey: saveKeyRef.current
      });
      setSuccessMsg('暫存草稿儲存成功');
      saveKeyRef.current = null; // 成功後清除以利下次操作
    } catch (err: any) {
      const errMsg = err.response?.data?.message || '儲存草稿失敗';
      setUploadError(errMsg);
    }
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setUploadError(null);
    setSuccessMsg(null);

    const isImage = file.type.startsWith('image/');
    const isVideo = file.type.startsWith('video/');

    if (!isImage && !isVideo) {
      setUploadError('僅支援上傳照片或影片檔案');
      if (fileInputRef.current) fileInputRef.current.value = '';
      return;
    }

    if (isImage) {
      // JPG/PNG/WebP 且 ≤1MB
      const ext = file.name.split('.').pop()?.toLowerCase();
      const validExt = ['jpg', 'jpeg', 'png', 'webp'].includes(ext || '');
      if (!validExt || file.size > 1 * 1024 * 1024) {
        setUploadError('圖片格式僅限 JPG/PNG/WebP 且大小不可超過 1MB');
        if (fileInputRef.current) fileInputRef.current.value = '';
        return;
      }
      setMediaFile(file);
    } else {
      // MP4/MOV 且 ≤50MB，時長 15~30s
      const ext = file.name.split('.').pop()?.toLowerCase();
      const validExt = ['mp4', 'mov'].includes(ext || '');
      if (!validExt || file.size > 50 * 1024 * 1024) {
        setUploadError('影片格式僅限 MP4/MOV 且大小不可超過 50MB');
        if (fileInputRef.current) fileInputRef.current.value = '';
        return;
      }

      // 檢查影片長度 (利用 HTML5 Video element)
      const video = document.createElement('video');
      video.preload = 'metadata';
      video.onloadedmetadata = () => {
        window.URL.revokeObjectURL(video.src);
        if (video.duration < 15 || video.duration > 30) {
          setUploadError('影片長度必須介於 15 至 30 秒之間');
          if (fileInputRef.current) fileInputRef.current.value = '';
          return;
        }
        setMediaFile(file);
      };
      video.onerror = () => {
        window.URL.revokeObjectURL(video.src);
        setUploadError('影片無法讀取，請確認檔案格式是否正確');
        if (fileInputRef.current) fileInputRef.current.value = '';
        setMediaFile(null);
      };
      video.src = URL.createObjectURL(file);
    }
  };

  const handleUploadMedia = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!mediaFile || !isEditable) return;

    if (!uploadKeyRef.current) {
      uploadKeyRef.current = getUuid();
    }

    setUploadError(null);
    setSuccessMsg(null);

    const mediaType = mediaFile.type.startsWith('video') ? 'VIDEO' : 'IMAGE';

    try {
      await uploadMutation.mutateAsync({
        file: mediaFile,
        mediaType,
        caption: mediaCaption,
        idempotencyKey: uploadKeyRef.current
      });
      setSuccessMsg('多媒體檔案上傳成功');
      setMediaFile(null);
      setMediaCaption('');
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
      uploadKeyRef.current = null;
    } catch (err: any) {
      const errMsg = err.response?.data?.message || '上傳多媒體失敗';
      setUploadError(errMsg);
    }
  };

  const handleDeleteMedia = async (mediaId: string, version: number) => {
    if (!isEditable) return;
    if (!deleteKeyRef.current[mediaId]) {
      deleteKeyRef.current[mediaId] = getUuid();
    }

    setUploadError(null);
    setSuccessMsg(null);

    try {
      await deleteMutation.mutateAsync({
        mediaId,
        version,
        idempotencyKey: deleteKeyRef.current[mediaId]
      });
      setSuccessMsg('媒體已成功刪除');
      delete deleteKeyRef.current[mediaId];
    } catch (err: any) {
      const errMsg = err.response?.data?.message || '刪除媒體失敗';
      setUploadError(errMsg);
    }
  };

  const handleSubmitReport = async () => {
    if (isSubmitted || report?.visitStatus !== 'DONE') return;
    if (!submitKeyRef.current) {
      submitKeyRef.current = getUuid();
    }

    setUploadError(null);
    setSuccessMsg(null);

    try {
      await submitMutation.mutateAsync(submitKeyRef.current);
      setSuccessMsg('照護日誌已成功送出！');
      submitKeyRef.current = null;
    } catch (err: any) {
      const errMsg = err.response?.data?.message || '送出日誌失敗';
      setUploadError(errMsg);
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', maxWidth: '800px', margin: '0 auto' }}>
      {/* 標題 */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '2rem'
        }}
      >
        <div>
          <h2
            style={{
              fontSize: '1.75rem',
              fontWeight: '700',
              color: 'var(--color-on-surface)',
              margin: 0
            }}
          >
            🐾 行程照護日誌回報
          </h2>
          <p
            style={{
              fontSize: '0.875rem',
              color: 'var(--color-on-surface-variant)',
              marginTop: '0.25rem'
            }}
          >
            請記錄本次行程照護細節。結束 24 小時後將自動鎖定逾期。
          </p>
        </div>
        <span
          style={{
            padding: '0.375rem 0.75rem',
            borderRadius: '9999px',
            fontSize: '0.75rem',
            fontWeight: '600',
            backgroundColor: badge.bg,
            color: badge.color
          }}
        >
          {badge.text}
        </span>
      </div>

      {/* 錯誤與成功訊息 */}
      {uploadError && (
        <div
          style={{
            padding: '1rem',
            borderRadius: '12px',
            backgroundColor: 'rgba(239, 68, 68, 0.1)',
            color: '#ef4444',
            marginBottom: '1.5rem',
            fontSize: '0.875rem',
            fontWeight: '500'
          }}
        >
          ⚠️ {uploadError}
        </div>
      )}
      {successMsg && (
        <div
          style={{
            padding: '1rem',
            borderRadius: '12px',
            backgroundColor: 'rgba(16, 185, 129, 0.1)',
            color: '#10b981',
            marginBottom: '1.5rem',
            fontSize: '0.875rem',
            fontWeight: '500'
          }}
        >
          ✅ {successMsg}
        </div>
      )}

      {/* 1. 待執行狀態 Check-in 面板 */}
      {report?.visitStatus === 'PENDING' && (
        <div
          style={{
            textAlign: 'center',
            padding: '4rem 2rem',
            backgroundColor: 'var(--color-surface-container)',
            borderRadius: '24px',
            border: '1px solid rgba(255, 255, 255, 0.05)',
            boxShadow: 'var(--shadow-ambient)',
            marginBottom: '2rem'
          }}
        >
          <p
            style={{
              color: 'var(--color-on-surface-variant)',
              marginBottom: '2.5rem',
              fontSize: '1.05rem',
              lineHeight: '1.6'
            }}
          >
            您已抵達現場嗎？請點擊下方按鈕進行服務 Check-in。
            <br />
            Check-in 後即可開始記錄照護項目並上傳多媒體日誌。
          </p>
          <button
            data-testid="sitter-report-start-visit-btn"
            onClick={handleStartVisit}
            disabled={startMutation.isPending}
            style={{
              padding: '1.25rem 3.5rem',
              borderRadius: '9999px',
              border: 'none',
              backgroundColor: '#10b981',
              color: 'white',
              fontSize: '1.25rem',
              fontWeight: '700',
              cursor: 'pointer',
              boxShadow: '0 10px 25px rgba(16, 185, 129, 0.25)',
              transition: 'all 0.2s'
            }}
          >
            {startMutation.isPending ? '正在啟動...' : '🟢 開始服務 (Check-in)'}
          </button>
        </div>
      )}

      {/* 2. 當非 PENDING 時，才顯示文字日誌與媒體編輯區 */}
      {report?.visitStatus !== 'PENDING' && (
        <>
          {/* 文字日誌編輯區 */}
          <div
            style={{
              backgroundColor: 'var(--color-surface-container)',
              borderRadius: '24px',
              padding: '1.5rem',
              marginBottom: '2rem',
              border: '1px solid rgba(255, 255, 255, 0.05)',
              boxShadow: 'var(--shadow-ambient)'
            }}
          >
            <h3
              style={{
                fontSize: '1.125rem',
                fontWeight: '600',
                color: 'var(--color-on-surface)',
                marginTop: 0,
                marginBottom: '1rem'
              }}
            >
              📝 文字日誌
            </h3>
            <textarea
              data-testid="sitter-report-content-input"
              value={content}
              onChange={(e) => isEditable && setContent(e.target.value.substring(0, 1000))}
              disabled={!isEditable}
              placeholder="請輸入貓咪今日的照護細節（如餵食量、精神狀態、大小便清潔等）..."
              rows={6}
              style={{
                width: '100%',
                backgroundColor: 'var(--color-surface-low)',
                color: 'var(--color-on-surface)',
                border: '1px solid rgba(255, 255, 255, 0.1)',
                borderRadius: '16px',
                padding: '1rem',
                fontSize: '0.95rem',
                lineHeight: '1.6',
                resize: 'vertical',
                outline: 'none',
                fontFamily: 'inherit',
                transition: 'border-color 0.2s',
                opacity: isEditable ? 1 : 0.6
              }}
            />
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginTop: '0.5rem'
              }}
            >
              <span style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)' }}>
                {content.length} / 1000 字
              </span>
              {isEditable && (
                <button
                  data-testid="sitter-report-save-draft-btn"
                  onClick={handleSaveDraft}
                  disabled={saveMutation.isPending}
                  style={{
                    padding: '0.5rem 1.25rem',
                    borderRadius: '9999px',
                    border: 'none',
                    backgroundColor: 'var(--color-primary-container)',
                    color: 'var(--color-primary)',
                    fontSize: '0.875rem',
                    fontWeight: '600',
                    cursor: 'pointer'
                  }}
                >
                  {saveMutation.isPending ? '暫存中...' : '暫存草稿'}
                </button>
              )}
            </div>
          </div>

          {/* 媒體管理區 */}
          <div
            style={{
              backgroundColor: 'var(--color-surface-container)',
              borderRadius: '24px',
              padding: '1.5rem',
              marginBottom: '2rem',
              border: '1px solid rgba(255, 255, 255, 0.05)',
              boxShadow: 'var(--shadow-ambient)'
            }}
          >
            <h3
              style={{
                fontSize: '1.125rem',
                fontWeight: '600',
                color: 'var(--color-on-surface)',
                marginTop: 0,
                marginBottom: '1.25rem'
              }}
            >
              📸 照護媒體清單 ({report?.media?.length || 0} 筆)
            </h3>

            {/* 媒體預覽照片牆 */}
            {report?.media && report.media.length > 0 ? (
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))',
                  gap: '1rem',
                  marginBottom: '1.5rem'
                }}
              >
                {report.media.map((item) => (
                  <div
                    key={item.mediaId}
                    style={{
                      position: 'relative',
                      borderRadius: '16px',
                      overflow: 'hidden',
                      aspectRatio: '1',
                      border: '1px solid rgba(255, 255, 255, 0.05)'
                    }}
                  >
                    {item.mediaType === 'VIDEO' ? (
                      <video
                        src={item.mediaUrl}
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        muted
                      />
                    ) : (
                      <img
                        src={item.mediaUrl}
                        alt={item.caption}
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                      />
                    )}
                    <div
                      style={{
                        position: 'absolute',
                        bottom: 0,
                        left: 0,
                        right: 0,
                        background: 'linear-gradient(to top, rgba(0,0,0,0.8), transparent)',
                        padding: '0.5rem',
                        color: '#fff',
                        fontSize: '0.75rem',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        overflow: 'hidden'
                      }}
                    >
                      {item.caption || (item.mediaType === 'VIDEO' ? '🎥 影片' : '📷 照片')}
                    </div>
                    {isEditable && (
                      <button
                        data-testid={`sitter-report-media-delete-${item.mediaId}`}
                        onClick={() => handleDeleteMedia(item.mediaId, item.version)}
                        style={{
                          position: 'absolute',
                          top: '0.5rem',
                          right: '0.5rem',
                          backgroundColor: 'rgba(239, 68, 68, 0.9)',
                          border: 'none',
                          borderRadius: '50%',
                          width: '24px',
                          height: '24px',
                          color: 'white',
                          fontWeight: 'bold',
                          cursor: 'pointer',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: '0.75rem'
                        }}
                      >
                        ×
                      </button>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p
                style={{
                  color: 'var(--color-on-surface-variant)',
                  fontSize: '0.9rem',
                  textAlign: 'center',
                  margin: '1.5rem 0'
                }}
              >
                尚無上傳照片或影片
              </p>
            )}

            {/* 上傳表單 */}
            {isEditable && (
              <form
                onSubmit={handleUploadMedia}
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '1rem',
                  borderTop: '1px solid rgba(255, 255, 255, 0.05)',
                  paddingTop: '1.25rem'
                }}
              >
                <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                  <input
                    data-testid="sitter-report-media-file-input"
                    ref={fileInputRef}
                    type="file"
                    accept="image/*,video/*"
                    onChange={handleFileChange}
                    style={{ display: 'none' }}
                    id="report-file-uploader"
                  />
                  <label
                    htmlFor="report-file-uploader"
                    style={{
                      padding: '0.625rem 1.25rem',
                      borderRadius: '9999px',
                      backgroundColor: 'var(--color-surface-low)',
                      color: 'var(--color-on-surface)',
                      fontSize: '0.875rem',
                      fontWeight: '600',
                      cursor: 'pointer',
                      border: '1px solid rgba(255, 255, 255, 0.1)'
                    }}
                  >
                    📁 選擇檔案
                  </label>
                  <span style={{ fontSize: '0.85rem', color: 'var(--color-on-surface-variant)' }}>
                    {mediaFile ? mediaFile.name : '未選擇任何檔案'}
                  </span>
                </div>

                <div style={{ display: 'flex', gap: '1rem' }}>
                  <input
                    data-testid="sitter-report-media-caption-input"
                    type="text"
                    value={mediaCaption}
                    onChange={(e) => setMediaCaption(e.target.value.substring(0, 100))}
                    placeholder="輸入媒體描述 (選填，最多 100 字)"
                    style={{
                      flex: 1,
                      backgroundColor: 'var(--color-surface-low)',
                      color: 'var(--color-on-surface)',
                      border: '1px solid rgba(255, 255, 255, 0.1)',
                      borderRadius: '12px',
                      padding: '0.5rem 1rem',
                      fontSize: '0.9rem',
                      outline: 'none'
                    }}
                  />
                  <button
                    data-testid="sitter-report-media-upload-btn"
                    type="submit"
                    disabled={!mediaFile || uploadMutation.isPending}
                    style={{
                      padding: '0.5rem 1.5rem',
                      borderRadius: '12px',
                      border: 'none',
                      backgroundColor: mediaFile
                        ? 'var(--color-primary)'
                        : 'var(--color-surface-low)',
                      color: mediaFile
                        ? 'var(--color-on-primary)'
                        : 'var(--color-on-surface-variant)',
                      fontSize: '0.9rem',
                      fontWeight: '600',
                      cursor: mediaFile ? 'pointer' : 'default'
                    }}
                  >
                    {uploadMutation.isPending ? '上傳中...' : '上傳'}
                  </button>
                </div>
              </form>
            )}
          </div>

          {/* 送出與操作按鈕區 */}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem' }}>
            {/* IN_PROGRESS 狀態下，最下方顯示結束服務 (Check-out) */}
            {report?.visitStatus === 'IN_PROGRESS' && (
              <button
                data-testid="sitter-report-end-visit-btn"
                onClick={handleEndVisit}
                disabled={endMutation.isPending}
                style={{
                  padding: '0.75rem 2rem',
                  borderRadius: '9999px',
                  border: 'none',
                  backgroundColor: '#ef4444',
                  color: 'white',
                  fontSize: '1rem',
                  fontWeight: '700',
                  cursor: 'pointer',
                  boxShadow: '0 8px 16px rgba(239, 68, 68, 0.2)'
                }}
              >
                {endMutation.isPending ? '正在結束...' : '🔴 完成服務 (Check-out)'}
              </button>
            )}

            {/* DONE 狀態下且非已送出，顯示正式送出日誌 */}
            {report?.visitStatus === 'DONE' && !isSubmitted && (
              <button
                data-testid="sitter-report-submit-btn"
                onClick={handleSubmitReport}
                disabled={submitMutation.isPending}
                style={{
                  padding: '0.75rem 2rem',
                  borderRadius: '9999px',
                  border: 'none',
                  backgroundColor: 'var(--color-primary)',
                  color: 'var(--color-on-primary)',
                  fontSize: '1rem',
                  fontWeight: '700',
                  cursor: 'pointer',
                  boxShadow: 'var(--shadow-ambient)'
                }}
              >
                {submitMutation.isPending ? '正在送出...' : '🚀 正式送出日誌'}
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
};

export default VisitReportManager;
