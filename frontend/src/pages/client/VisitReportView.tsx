import React, { useState } from 'react';
import { useVisitReportQuery } from '../../hooks/useVisitReport';
import LightBox from '../../components/ui/LightBox';

interface VisitReportViewProps {
  visitId: string;
}

const VisitReportView: React.FC<VisitReportViewProps> = ({ visitId }) => {
  const { data: report, isLoading } = useVisitReportQuery(visitId);

  // Lightbox State
  const [selectedImageUrl, setSelectedImageUrl] = useState<string | null>(null);
  const [selectedImageCaption, setSelectedImageCaption] = useState('');

  if (isLoading) {
    return (
      <div
        style={{ padding: '2rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
      >
        正在載入照護回報日誌...
      </div>
    );
  }

  // 判定是否查無資料 (因為未送出的 DRAFT 在飼主端查詢時後端會回傳 404，由 useVisitReportQuery 捕獲並包裝)
  // 如果 report.reportId 為空，或是狀態不是 SUBMITTED，對飼主皆視為「尚未回報」
  const hasNoReport = !report || !report.reportId || report.status !== 'SUBMITTED';

  if (hasNoReport) {
    return (
      <div
        style={{ padding: '3rem 1.5rem', textAlign: 'center', maxWidth: '600px', margin: '0 auto' }}
      >
        <div
          style={{
            backgroundColor: 'var(--color-surface-container)',
            borderRadius: '28px',
            padding: '2.5rem',
            border: '1px solid rgba(255, 255, 255, 0.05)',
            boxShadow: 'var(--shadow-ambient)'
          }}
          data-testid="client-report-empty-state"
        >
          <div style={{ fontSize: '3rem', marginBottom: '1rem' }}>⏳</div>
          <h3
            style={{
              fontSize: '1.25rem',
              fontWeight: '700',
              color: 'var(--color-on-surface)',
              margin: 0
            }}
          >
            尚無照護回報
          </h3>
          <p
            style={{
              fontSize: '0.9rem',
              color: 'var(--color-on-surface-variant)',
              marginTop: '0.5rem',
              lineHeight: '1.6'
            }}
          >
            保母目前正在準備或尚未送出本次行程的照護日誌，請稍候再試。一旦保母送出日誌，您將會收到通知！
          </p>
        </div>
      </div>
    );
  }

  return (
    <div
      style={{ padding: '2rem 1.5rem', maxWidth: '800px', margin: '0 auto' }}
      data-testid="client-report-view-container"
    >
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
            以下為保母於行程結束後為您回報的即時狀態。
          </p>
        </div>
        <span
          style={{
            padding: '0.375rem 0.75rem',
            borderRadius: '9999px',
            fontSize: '0.75rem',
            fontWeight: '600',
            backgroundColor: 'var(--color-primary-container)',
            color: 'var(--color-primary)'
          }}
        >
          已送出
        </span>
      </div>

      {/* 文字日誌 */}
      <div
        style={{
          backgroundColor: 'var(--color-surface-container)',
          borderRadius: '24px',
          padding: '1.5rem',
          marginBottom: '2rem',
          border: '1px solid rgba(255, 255, 255, 0.05)',
          boxShadow: 'var(--shadow-ambient)'
        }}
        data-testid="client-report-text-card"
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
          📝 照護日誌內容
        </h3>
        <p
          style={{
            color: 'var(--color-on-surface)',
            fontSize: '0.95rem',
            lineHeight: '1.7',
            whiteSpace: 'pre-wrap',
            margin: 0
          }}
        >
          {report.content || '保母未填寫文字敘述。'}
        </p>
        {report.submittedAt && (
          <div
            style={{
              fontSize: '0.75rem',
              color: 'var(--color-on-surface-variant)',
              textAlign: 'right',
              marginTop: '1rem'
            }}
          >
            回報時間：
            {new Date(report.submittedAt).toLocaleString('zh-TW', { timeZone: 'Asia/Taipei' })}
          </div>
        )}
      </div>

      {/* 媒體檔案 */}
      <div
        style={{
          backgroundColor: 'var(--color-surface-container)',
          borderRadius: '24px',
          padding: '1.5rem',
          marginBottom: '2rem',
          border: '1px solid rgba(255, 255, 255, 0.05)',
          boxShadow: 'var(--shadow-ambient)'
        }}
        data-testid="client-report-media-card"
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
          📸 回報現場相簿 ({report.media?.length || 0} 筆)
        </h3>

        {report.media && report.media.length > 0 ? (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
              gap: '1rem'
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
                  border: '1px solid rgba(255, 255, 255, 0.05)',
                  cursor: item.mediaType === 'IMAGE' ? 'pointer' : 'default'
                }}
                onClick={() => {
                  if (item.mediaType === 'IMAGE') {
                    setSelectedImageUrl(item.mediaUrl);
                    setSelectedImageCaption(item.caption || '現場照片');
                  }
                }}
              >
                {item.mediaType === 'VIDEO' ? (
                  <video
                    src={item.mediaUrl}
                    style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    controls
                  />
                ) : (
                  <img
                    src={item.mediaUrl}
                    alt={item.caption}
                    style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                  />
                )}
                {item.caption && (
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
                    {item.caption}
                  </div>
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
              margin: '1rem 0'
            }}
          >
            保母無上傳照片或影片。
          </p>
        )}
      </div>

      {/* Lightbox 放大展示 */}
      <LightBox
        isOpen={selectedImageUrl !== null}
        onClose={() => setSelectedImageUrl(null)}
        mediaUrl={selectedImageUrl || ''}
        caption={selectedImageCaption}
      />
    </div>
  );
};

export default VisitReportView;
