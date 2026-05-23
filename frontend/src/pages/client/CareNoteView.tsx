import React, { useState } from 'react';
import { useCareNoteQuery } from '../../hooks/useCareNote';
import { useCareMediaQuery } from '../../hooks/useCareMedia';
import LightBox from '../../components/ui/LightBox';
import type { CareMedia } from '../../api/careApi';

interface CareNoteViewProps {
  sitterId: string;
  ownerId: string;
}

const SECTION_LABELS: Record<string, string> = {
  SERVICE: '🐾 服務項目',
  CONTACT: '📞 聯絡資訊',
  WARNING: '⚠️ 重要警示',
  PREFERENCE: '❤️ 喜好與禁忌',
  HOSPITAL: '🏥 特約醫院',
  OTHER: '📝 其他說明'
};

const CareNoteView: React.FC<CareNoteViewProps> = ({ sitterId, ownerId }) => {
  const { data: careNote, isLoading: isNoteLoading } = useCareNoteQuery(sitterId, ownerId);
  const { data: mediaItems = [], isLoading: isMediaLoading } = useCareMediaQuery(sitterId, ownerId);

  const [activeTab, setActiveTab] = useState<'notes' | 'photos'>('notes');
  const [selectedPhoto, setSelectedPhoto] = useState<CareMedia | null>(null);

  if (isNoteLoading || isMediaLoading) {
    return <div style={{ padding: '2rem', textAlign: 'center' }}>讀取照護備忘錄中...</div>;
  }

  // 取得各分區已排序的項目
  const getSortedItems = (secType: string) => {
    const items = careNote?.sections?.[secType] || [];
    return [...items].sort((a, b) => a.sortOrder - b.sortOrder);
  };

  const sectionsExist =
    careNote?.sections &&
    Object.values(careNote.sections).some((secItems) => secItems.length > 0);

  return (
    <div style={{ padding: '1rem 0', display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Header */}
      <div>
        <h1 style={{ margin: 0, fontSize: '2rem', color: 'var(--color-primary)', fontFamily: 'var(--font-display)' }}>
          愛貓照護日誌
        </h1>
        <p style={{ margin: '0.4rem 0 0 0', color: 'var(--color-on-surface-variant)', fontSize: '0.9rem' }}>
          飼主唯讀檢視面板 · 隨時掌握現場照護狀態
        </p>
      </div>

      {/* Segmented Tab Navigation */}
      <div
        style={{
          display: 'flex',
          background: 'var(--color-surface-low)',
          padding: '0.4rem',
          borderRadius: '9999px',
          boxShadow: 'inset 0 2px 4px rgba(0,0,0,0.02)'
        }}
      >
        <button
          onClick={() => setActiveTab('notes')}
          style={{
            flex: 1,
            border: 'none',
            background: activeTab === 'notes' ? 'var(--color-surface-lowest)' : 'none',
            color: activeTab === 'notes' ? 'var(--color-primary)' : 'var(--color-on-surface-variant)',
            padding: '0.8rem 0',
            borderRadius: '9999px',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '0.95rem',
            boxShadow: activeTab === 'notes' ? '0 2px 8px rgba(0,0,0,0.04)' : 'none',
            transition: 'all 0.2s ease',
            fontFamily: 'inherit'
          }}
        >
          📋 照護項目
        </button>
        <button
          onClick={() => setActiveTab('photos')}
          style={{
            flex: 1,
            border: 'none',
            background: activeTab === 'photos' ? 'var(--color-surface-lowest)' : 'none',
            color: activeTab === 'photos' ? 'var(--color-primary)' : 'var(--color-on-surface-variant)',
            padding: '0.8rem 0',
            borderRadius: '9999px',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '0.95rem',
            boxShadow: activeTab === 'photos' ? '0 2px 8px rgba(0,0,0,0.04)' : 'none',
            transition: 'all 0.2s ease',
            fontFamily: 'inherit'
          }}
        >
          📸 現場照片 ({mediaItems.length})
        </button>
      </div>

      {/* Tab Contents */}
      {activeTab === 'notes' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          {!sectionsExist ? (
            <div className="card-layered" style={{ textAlign: 'center', padding: '4rem', opacity: 0.6 }}>
              保母目前尚未填寫任何照護備忘錄項目。
            </div>
          ) : (
            Object.keys(SECTION_LABELS).map((secType) => {
              const sorted = getSortedItems(secType);
              if (sorted.length === 0) return null;

              return (
                <div
                  key={secType}
                  className="card-layered"
                  style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}
                  data-testid={`client-carenote-section-${secType}`}
                >
                  <h3 style={{ margin: 0, fontSize: '1.2rem', color: 'var(--color-primary)', borderBottom: '1px solid var(--color-outline-variant)', paddingBottom: '0.5rem' }}>
                    {SECTION_LABELS[secType]}
                  </h3>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                    {sorted.map((item, idx) => (
                      <div
                        key={item.id}
                        style={{
                          background: 'var(--color-surface-low)',
                          padding: '1rem 1.2rem',
                          borderRadius: 'var(--radius-lg)'
                        }}
                      >
                        <strong style={{ display: 'block', fontSize: '1.05rem', marginBottom: '0.4rem', color: 'var(--color-on-surface)' }}>
                          {idx + 1}. {item.title || '無標題'}
                        </strong>
                        <p style={{ margin: 0, fontSize: '0.95rem', color: 'var(--color-on-surface-variant)', lineHeight: '1.5' }}>
                          {item.content || '無說明'}
                        </p>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })
          )}
        </div>
      ) : (
        /* Photos Tab */
        <div>
          {mediaItems.length === 0 ? (
            <div className="card-layered" style={{ textAlign: 'center', padding: '4rem', opacity: 0.6 }}>
              保母目前尚未上傳任何照護照片。
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '1rem' }}>
              {mediaItems.map((media) => (
                <div
                  key={media.id}
                  onClick={() => setSelectedPhoto(media)}
                  style={{
                    position: 'relative',
                    borderRadius: 'var(--radius-lg)',
                    overflow: 'hidden',
                    aspectRatio: '1',
                    background: 'var(--color-surface-low)',
                    boxShadow: 'var(--shadow-ambient)',
                    border: '1px solid var(--color-outline-variant)',
                    cursor: 'pointer'
                  }}
                >
                  <img
                    src={media.mediaUrl}
                    alt={media.caption}
                    style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                  />
                  <div
                    style={{
                      position: 'absolute',
                      bottom: 0,
                      left: 0,
                      right: 0,
                      background: 'linear-gradient(transparent, rgba(0,0,0,0.8))',
                      color: 'white',
                      padding: '0.8rem 0.5rem 0.5rem 0.5rem',
                      fontSize: '0.8rem'
                    }}
                  >
                    {media.caption || '無描述'}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* LightBox Portal Overlay */}
      {selectedPhoto && (
        <LightBox
          isOpen={!!selectedPhoto}
          onClose={() => setSelectedPhoto(null)}
          mediaUrl={selectedPhoto.mediaUrl}
          caption={selectedPhoto.caption}
        />
      )}
    </div>
  );
};

export default CareNoteView;
