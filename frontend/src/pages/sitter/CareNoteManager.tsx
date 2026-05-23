import React, { useEffect, useState, useRef } from 'react';
import { useCareNoteQuery, useSaveCareNoteMutation, useApplyTemplateMutation, useCreateTemplateMutation, useTemplatesQuery } from '../../hooks/useCareNote';
import { useCareMediaQuery, useUploadMediaMutation, useDeleteMediaMutation } from '../../hooks/useCareMedia';
import BottomSheet from '../../components/modals/BottomSheet';
import TemplateManagerModal from '../../components/modals/TemplateManagerModal';
import type { CareNoteItemDto } from '../../api/careApi';

interface CareNoteManagerProps {
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

const CareNoteManager: React.FC<CareNoteManagerProps> = ({ sitterId, ownerId }) => {
  const { data: careNote, isLoading: isNoteLoading } = useCareNoteQuery(sitterId, ownerId);
  const { data: mediaItems = [], isLoading: isMediaLoading } = useCareMediaQuery(sitterId, ownerId);

  const saveMutation = useSaveCareNoteMutation(sitterId, ownerId);
  const applyTemplateMutation = useApplyTemplateMutation(sitterId, ownerId);
  const saveAsTemplateMutation = useCreateTemplateMutation();
  const uploadMediaMutation = useUploadMediaMutation(sitterId, ownerId);
  const deleteMediaMutation = useDeleteMediaMutation(sitterId, ownerId);

  const { data: templates = [] } = useTemplatesQuery();

  const [items, setItems] = useState<CareNoteItemDto[]>([]);
  const [isTemplateSheetOpen, setIsTemplateSheetOpen] = useState(false);
  const [isTemplateModalOpen, setIsTemplateModalOpen] = useState(false);

  // 另存為範本相關 State
  const [isSaveAsModalOpen, setIsSaveAsModalOpen] = useState(false);
  const [newTemplateName, setNewTemplateName] = useState('');

  // 媒體上傳 State
  const [mediaFile, setMediaFile] = useState<File | null>(null);
  const [mediaCaption, setMediaCaption] = useState('');

  // 冪等性 Key Refs
  const saveKeyRef = useRef<string | null>(null);
  const applyKeyRef = useRef<string | null>(null);
  const uploadKeyRef = useRef<string | null>(null);
  const saveAsTemplateKeyRef = useRef<string | null>(null);
  const deleteKeyRef = useRef<Record<string, string>>({});

  // 載入記事本資料並平鋪為前端 state
  useEffect(() => {
    if (careNote?.sections) {
      const flatItems: CareNoteItemDto[] = [];
      const sectionKeys: (keyof typeof SECTION_LABELS)[] = ['SERVICE', 'CONTACT', 'WARNING', 'PREFERENCE', 'HOSPITAL', 'OTHER'];
      sectionKeys.forEach((secType) => {
        const secItems = careNote.sections[secType] || [];
        // 依據 sortOrder 進行排列
        const sorted = [...secItems].sort((a, b) => a.sortOrder - b.sortOrder);
        sorted.forEach((item) => {
          flatItems.push({
            sectionType: item.sectionType,
            title: item.title,
            content: item.content
          });
        });
      });
      setItems(flatItems);
    }
  }, [careNote]);

  const handleAddItem = (type: CareNoteItemDto['sectionType']) => {
    setItems((prev) => [...prev, { sectionType: type, title: '', content: '' }]);
  };

  const handleRemoveItem = (index: number) => {
    setItems((prev) => prev.filter((_, i) => i !== index));
  };

  const handleItemChange = (index: number, field: 'title' | 'content', value: string) => {
    setItems((prev) => {
      const updated = [...prev];
      updated[index] = { ...updated[index], [field]: value };
      return updated;
    });
  };

  const moveItem = (index: number, direction: 'up' | 'down') => {
    setItems((prev) => {
      const type = prev[index].sectionType;
      // 找出同分區的所有項目在 prev 中的原始 index 對照
      const sectionIndices = prev
        .map((item, idx) => ({ item, idx }))
        .filter((x) => x.item.sectionType === type)
        .map((x) => x.idx);

      const pos = sectionIndices.indexOf(index);
      if (direction === 'up' && pos > 0) {
        const swapIndex = sectionIndices[pos - 1];
        const nextItems = [...prev];
        const temp = nextItems[index];
        nextItems[index] = nextItems[swapIndex];
        nextItems[swapIndex] = temp;
        return nextItems;
      }
      if (direction === 'down' && pos < sectionIndices.length - 1) {
        const swapIndex = sectionIndices[pos + 1];
        const nextItems = [...prev];
        const temp = nextItems[index];
        nextItems[index] = nextItems[swapIndex];
        nextItems[swapIndex] = temp;
        return nextItems;
      }
      return prev;
    });
  };

  // 1. 儲存記事本 (PUT)
  const handleSaveNote = () => {
    if (!saveKeyRef.current) {
      saveKeyRef.current = crypto.randomUUID();
    }
    saveMutation.mutate(
      { items, idempotencyKey: saveKeyRef.current },
      {
        onSuccess: () => {
          saveKeyRef.current = null;
          alert('記事本儲存成功');
        },
        onError: (err: any) => {
          if (err.response?.status === 409) {
            alert('重複請求處理中，請稍候');
          } else {
            alert(err.response?.data?.message || '儲存失敗');
            saveKeyRef.current = null;
          }
        }
      }
    );
  };

  // 2. 套用模板 (POST apply-template)
  const handleApplyTemplate = (templateId: string) => {
    if (!applyKeyRef.current) {
      applyKeyRef.current = crypto.randomUUID();
    }
    applyTemplateMutation.mutate(
      { templateId, idempotencyKey: applyKeyRef.current },
      {
        onSuccess: () => {
          applyKeyRef.current = null;
          setIsTemplateSheetOpen(false);
          alert('範本套用成功 (追加至各分區末尾)');
        },
        onError: (err: any) => {
          if (err.response?.status === 409) {
            alert('重複請求處理中，請稍候');
          } else {
            alert(err.response?.data?.message || '套用範本失敗');
            applyKeyRef.current = null;
          }
        }
      }
    );
  };

  // 3. 另存為模板
  const handleSaveAsTemplate = () => {
    if (!newTemplateName.trim()) {
      alert('請輸入範本名稱');
      return;
    }
    if (items.length === 0) {
      alert('當前記事本無項目，無法另存');
      return;
    }

    if (!saveAsTemplateKeyRef.current) {
      saveAsTemplateKeyRef.current = crypto.randomUUID();
    }

    const payload = {
      name: newTemplateName,
      items: items.map((item) => ({
        sectionType: item.sectionType,
        title: item.title,
        content: item.content
      }))
    };

    saveAsTemplateMutation.mutate(
      { dto: payload, idempotencyKey: saveAsTemplateKeyRef.current },
      {
        onSuccess: () => {
          saveAsTemplateKeyRef.current = null;
          setIsSaveAsModalOpen(false);
          setNewTemplateName('');
          alert('另存範本成功');
        },
        onError: (err: any) => {
          if (err.response?.status === 409) {
            alert('重複請求處理中，請稍候');
          } else {
            alert(err.response?.data?.message || '另存範本失敗');
            saveAsTemplateKeyRef.current = null;
          }
        }
      }
    );
  };

  // 4. 媒體上傳 (MIME, 10MB 與 20筆防禦)
  const handleMediaUpload = (e: React.FormEvent) => {
    e.preventDefault();
    if (!mediaFile) {
      alert('請先選擇檔案');
      return;
    }

    // A. 檔案類型防禦
    if (!mediaFile.type.startsWith('image/')) {
      alert('僅限上傳圖片檔案 (accept="image/*")');
      return;
    }

    // B. 單檔 10MB 上限防禦
    const maxSize = 10 * 1024 * 1024;
    if (mediaFile.size > maxSize) {
      alert('單檔大小不能超過 10MB');
      return;
    }

    // C. 20 筆總數上限防禦
    if (mediaItems.length >= 20) {
      alert('媒體照片牆已達 20 筆上限，請先刪除部分照片');
      return;
    }

    if (!uploadKeyRef.current) {
      uploadKeyRef.current = crypto.randomUUID();
    }

    uploadMediaMutation.mutate(
      { file: mediaFile, caption: mediaCaption, idempotencyKey: uploadKeyRef.current },
      {
        onSuccess: () => {
          uploadKeyRef.current = null;
          setMediaFile(null);
          setMediaCaption('');
          // 清空 file input value
          const fileInput = document.getElementById('media-upload-input') as HTMLInputElement;
          if (fileInput) fileInput.value = '';
          alert('圖片上傳成功');
        },
        onError: (err: any) => {
          if (err.response?.status === 409) {
            alert('重複請求處理中，請稍候');
          } else {
            alert(err.response?.data?.message || '上傳失敗');
            uploadKeyRef.current = null;
          }
        }
      }
    );
  };

  // 5. 媒體刪除
  const handleMediaDelete = (mediaId: string) => {
    if (!confirm('確定要刪除這張照片嗎？')) return;

    if (!deleteKeyRef.current[mediaId]) {
      deleteKeyRef.current[mediaId] = crypto.randomUUID();
    }

    deleteMediaMutation.mutate(
      { mediaId, idempotencyKey: deleteKeyRef.current[mediaId] },
      {
        onSuccess: () => {
          // 主動清理 key 以防洩漏
          delete deleteKeyRef.current[mediaId];
        },
        onError: (err: any) => {
          if (err.response?.status === 409) {
            alert('重複請求處理中，請稍候');
          } else {
            alert(err.response?.data?.message || '刪除失敗');
            delete deleteKeyRef.current[mediaId];
          }
        }
      }
    );
  };

  if (isNoteLoading || isMediaLoading) {
    return <div style={{ padding: '2rem', textAlign: 'center' }}>讀取照護資料中...</div>;
  }

  return (
    <div style={{ padding: '1rem 0', display: 'flex', flexDirection: 'column', gap: '2rem' }}>
      {/* Page Header (Asymmetric Layout) */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '2rem', color: 'var(--color-primary)', fontFamily: 'var(--font-display)' }}>
            照護主頁與媒體庫
          </h1>
          <p style={{ margin: '0.4rem 0 0 0', color: 'var(--color-on-surface-variant)', fontSize: '0.9rem' }}>
            保母專屬管理面板 · Stitch 無框線極簡設計
          </p>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button
            className="btn-primary"
            style={{ background: 'none', border: '1px solid var(--color-surface-high)', color: 'var(--color-on-surface)' }}
            onClick={() => setIsTemplateModalOpen(true)}
            data-testid="sitter-carenote-template-manage"
          >
            📋 管理範本
          </button>
          <button
            className="btn-primary"
            style={{ background: 'none', border: '1px solid var(--color-surface-high)', color: 'var(--color-on-surface)' }}
            onClick={() => setIsTemplateSheetOpen(true)}
            data-testid="sitter-carenote-template-open"
          >
            ⚡ 套用範本
          </button>
        </div>
      </div>

      {/* Grid: Left - Care Note Edit, Right - Media Upload & Wall */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '2rem' }}>
        
        {/* Care Note Editor */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h2 style={{ margin: 0, fontSize: '1.4rem', fontFamily: 'var(--font-display)' }}>照護備忘錄項目</h2>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button
                className="btn-primary"
                onClick={() => setIsSaveAsModalOpen(true)}
                data-testid="sitter-carenote-template-saveas"
                style={{ padding: '0.6rem 1.2rem', fontSize: '0.9rem', background: 'none', color: 'var(--color-primary)', border: '1px solid var(--color-primary)' }}
              >
                另存為範本
              </button>
              <button
                className="btn-primary"
                onClick={handleSaveNote}
                data-testid="sitter-carenote-save-btn"
                disabled={saveMutation.isPending}
                style={{ padding: '0.6rem 1.5rem', fontSize: '0.9rem' }}
              >
                {saveMutation.isPending ? '儲存中...' : '儲存備忘錄'}
              </button>
            </div>
          </div>

          {/* 6 Sections Rendering */}
          {(['SERVICE', 'CONTACT', 'WARNING', 'PREFERENCE', 'HOSPITAL', 'OTHER'] as const).map((secType) => {
            const sectionItems = items.map((item, idx) => ({ item, idx })).filter((x) => x.item.sectionType === secType);

            return (
              <div key={secType} className="card-layered" style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <h3 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--color-primary)' }}>
                    {SECTION_LABELS[secType]}
                  </h3>
                  <button
                    onClick={() => handleAddItem(secType)}
                    data-testid={`sitter-carenote-add-item-${secType}`}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: 'var(--color-primary)',
                      cursor: 'pointer',
                      fontWeight: 600,
                      fontSize: '0.9rem'
                    }}
                  >
                    + 新增條目
                  </button>
                </div>

                {sectionItems.length === 0 ? (
                  <div style={{ padding: '1rem', textAlign: 'center', color: 'var(--color-on-surface-variant)', fontSize: '0.9rem', opacity: 0.6 }}>
                    此分區目前沒有條目
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                    {sectionItems.map(({ item, idx }, pos) => (
                      <div
                        key={idx}
                        style={{
                          background: 'var(--color-surface-low)',
                          padding: '1rem',
                          borderRadius: 'var(--radius-sm)',
                          display: 'flex',
                          flexDirection: 'column',
                          gap: '0.8rem',
                          position: 'relative'
                        }}
                      >
                        {/* Remove button */}
                        <button
                          onClick={() => handleRemoveItem(idx)}
                          style={{
                            position: 'absolute',
                            top: '12px',
                            right: '12px',
                            border: 'none',
                            background: 'none',
                            color: 'var(--color-error)',
                            cursor: 'pointer',
                            fontSize: '0.85rem'
                          }}
                        >
                          移除
                        </button>

                        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', paddingRight: '2.5rem' }}>
                          {/* Sort buttons */}
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.2rem' }}>
                            <button
                              onClick={() => moveItem(idx, 'up')}
                              disabled={pos === 0}
                              style={{
                                border: 'none',
                                background: 'none',
                                cursor: pos === 0 ? 'not-allowed' : 'pointer',
                                opacity: pos === 0 ? 0.3 : 0.7,
                                fontSize: '0.9rem'
                              }}
                            >
                              ▲
                            </button>
                            <button
                              onClick={() => moveItem(idx, 'down')}
                              disabled={pos === sectionItems.length - 1}
                              style={{
                                border: 'none',
                                background: 'none',
                                cursor: pos === sectionItems.length - 1 ? 'not-allowed' : 'pointer',
                                opacity: pos === sectionItems.length - 1 ? 0.3 : 0.7,
                                fontSize: '0.9rem'
                              }}
                            >
                              ▼
                            </button>
                          </div>

                          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', flex: 1 }}>
                            <input
                              type="text"
                              value={item.title}
                              onChange={(e) => handleItemChange(idx, 'title', e.target.value)}
                              data-testid="sitter-carenote-item-title"
                              placeholder="例如：早晨餵罐頭"
                              style={{
                                padding: '0.5rem',
                                borderRadius: 'var(--radius-sm)',
                                border: '1px solid var(--color-surface-high)',
                                fontSize: '0.95rem'
                              }}
                            />
                            <textarea
                              value={item.content}
                              onChange={(e) => handleItemChange(idx, 'content', e.target.value)}
                              data-testid="sitter-carenote-item-content"
                              placeholder="寫下具體指示或照護細節..."
                              rows={2}
                              style={{
                                padding: '0.5rem',
                                borderRadius: 'var(--radius-sm)',
                                border: '1px solid var(--color-surface-high)',
                                fontSize: '0.9rem',
                                fontFamily: 'inherit',
                                resize: 'vertical'
                              }}
                            />
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* Media Management */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <h2 style={{ margin: 0, fontSize: '1.4rem', fontFamily: 'var(--font-display)' }}>照護照片與媒體牆 ({mediaItems.length}/20)</h2>

          {/* Upload Card */}
          <div className="card-layered">
            <h3 style={{ margin: '0 0 1rem 0', fontSize: '1.1rem', color: 'var(--color-primary)' }}>上傳現場照護照片</h3>
            <form onSubmit={handleMediaUpload} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <div>
                <input
                  id="media-upload-input"
                  type="file"
                  accept="image/*"
                  onChange={(e) => setMediaFile(e.target.files?.[0] || null)}
                  data-testid="sitter-carenote-media-file"
                  style={{ width: '100%' }}
                />
                <p style={{ margin: '0.3rem 0 0 0', fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                  支援 PNG, JPG, WEBP (單檔最大 10MB)
                </p>
              </div>

              <input
                type="text"
                placeholder="輸入照片描述 (例如：飼料盆已洗淨)"
                value={mediaCaption}
                onChange={(e) => setMediaCaption(e.target.value)}
                data-testid="sitter-carenote-media-caption"
                style={{
                  padding: '0.7rem',
                  borderRadius: 'var(--radius-sm)',
                  border: '1px solid var(--color-surface-high)'
                }}
              />

              <button
                type="submit"
                className="btn-primary"
                data-testid="sitter-carenote-media-submit"
                disabled={uploadMediaMutation.isPending}
              >
                {uploadMediaMutation.isPending ? '圖片上傳中...' : '確認上傳照片'}
              </button>
            </form>
          </div>

          {/* Media Wall */}
          {mediaItems.length === 0 ? (
            <div className="card-layered" style={{ textAlign: 'center', padding: '3rem', opacity: 0.6 }}>
              照片牆目前空空如也，快幫貓咪拍照上傳吧！
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '1rem' }}>
              {mediaItems.map((media) => (
                <div
                  key={media.id}
                  style={{
                    position: 'relative',
                    borderRadius: 'var(--radius-lg)',
                    overflow: 'hidden',
                    aspectRatio: '1',
                    background: 'var(--color-surface-low)',
                    boxShadow: 'var(--shadow-ambient)',
                    border: '1px solid var(--color-outline-variant)'
                  }}
                  className="media-item-container"
                >
                  <img
                    src={media.mediaUrl}
                    alt={media.caption}
                    style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                  />

                  {/* Caption & Overlay */}
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

                  {/* Hover Delete Button */}
                  <button
                    onClick={() => handleMediaDelete(media.id)}
                    data-testid={`sitter-carenote-media-delete-${media.id}`}
                    className="delete-overlay-btn"
                    style={{
                      position: 'absolute',
                      top: '8px',
                      right: '8px',
                      background: 'rgba(255, 255, 255, 0.9)',
                      border: 'none',
                      borderRadius: '50%',
                      width: '28px',
                      height: '28px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      cursor: 'pointer',
                      color: 'var(--color-error)',
                      fontWeight: 'bold',
                      fontSize: '0.9rem',
                      boxShadow: '0 2px 8px rgba(0,0,0,0.1)'
                    }}
                  >
                    🗑️
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* 5. 另存為範本 Portal Modal */}
      {isSaveAsModalOpen && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            zIndex: 1150,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'rgba(45, 47, 46, 0.5)',
            backdropFilter: 'blur(8px)',
            WebkitBackdropFilter: 'blur(8px)'
          }}
          data-testid="saveas-modal-backdrop"
        >
          <div
            className="card-layered"
            style={{ width: '90%', maxWidth: '400px', background: 'var(--color-surface-lowest)' }}
          >
            <h3 style={{ margin: '0 0 1rem 0', fontFamily: 'var(--font-display)', color: 'var(--color-primary)' }}>
              另存為照護範本
            </h3>
            <input
              type="text"
              placeholder="請輸入範本名稱"
              value={newTemplateName}
              onChange={(e) => setNewTemplateName(e.target.value)}
              data-testid="sitter-template-saveas-name-input"
              style={{
                width: '100%',
                padding: '0.8rem',
                borderRadius: 'var(--radius-sm)',
                border: '1px solid var(--color-surface-high)',
                boxSizing: 'border-box',
                marginBottom: '1.5rem'
              }}
            />
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem' }}>
              <button
                className="btn-primary"
                onClick={() => {
                  setIsSaveAsModalOpen(false);
                  setNewTemplateName('');
                  saveAsTemplateKeyRef.current = null;
                }}
                style={{ background: 'none', color: 'var(--color-on-surface)', border: '1px solid var(--color-surface-high)' }}
              >
                取消
              </button>
              <button
                className="btn-primary"
                onClick={handleSaveAsTemplate}
                data-testid="sitter-template-saveas-confirm-btn"
                disabled={saveAsTemplateMutation.isPending}
              >
                {saveAsTemplateMutation.isPending ? '建立中...' : '確認另存'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* BottomSheet for Applying Template */}
      <BottomSheet
        isOpen={isTemplateSheetOpen}
        onClose={() => {
          setIsTemplateSheetOpen(false);
          applyKeyRef.current = null;
        }}
        title="⚡ 選擇並追加照護範本"
      >
        {templates.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '2rem', color: 'var(--color-on-surface-variant)' }}>
            您目前沒有任何範本。請先點選管理範本建立。
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {templates.map((tpl) => (
              <button
                key={tpl.id}
                onClick={() => handleApplyTemplate(tpl.id)}
                data-testid={`sitter-carenote-template-item-${tpl.id}`}
                disabled={applyTemplateMutation.isPending}
                style={{
                  width: '100%',
                  padding: '1.2rem 1rem',
                  background: 'var(--color-surface-low)',
                  border: '1px solid var(--color-outline-variant)',
                  borderRadius: 'var(--radius-lg)',
                  cursor: 'pointer',
                  textAlign: 'left',
                  transition: 'background 0.2s ease',
                  fontFamily: 'inherit'
                }}
                onMouseOver={(e) => (e.currentTarget.style.background = 'var(--color-surface-high)')}
                onMouseOut={(e) => (e.currentTarget.style.background = 'var(--color-surface-low)')}
              >
                <strong style={{ display: 'block', fontSize: '1rem', color: 'var(--color-on-surface)' }}>
                  {tpl.name}
                </strong>
                <span style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)' }}>
                  包含 {tpl.items?.length || 0} 個照護項目
                </span>
              </button>
            ))}
          </div>
        )}
      </BottomSheet>

      {/* Template Manager Portal Modal */}
      <TemplateManagerModal isOpen={isTemplateModalOpen} onClose={() => setIsTemplateModalOpen(false)} />
    </div>
  );
};

export default CareNoteManager;
