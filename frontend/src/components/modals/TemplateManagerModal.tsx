import React, { useState, useRef } from 'react';
import { createPortal } from 'react-dom';
import {
  useTemplatesQuery,
  useCreateTemplateMutation,
  useUpdateTemplateMutation,
  useDeleteTemplateMutation
} from '../../hooks/useCareNote';
import type { CareTemplate, CareNoteItemDto } from '../../api/careApi';

interface TemplateManagerModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const TemplateManagerModal: React.FC<TemplateManagerModalProps> = ({ isOpen, onClose }) => {
  const { data: templates = [], isLoading } = useTemplatesQuery();
  const createMutation = useCreateTemplateMutation();
  const updateMutation = useUpdateTemplateMutation();
  const deleteMutation = useDeleteTemplateMutation();

  const [editingTemplate, setEditingTemplate] = useState<CareTemplate | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const [templateName, setTemplateName] = useState('');
  const [templateItems, setTemplateItems] = useState<CareNoteItemDto[]>([]);

  // 獨立的 Idempotency Key Refs
  const createKeyRef = useRef<string | null>(null);
  const updateKeyRef = useRef<string | null>(null);
  const deleteKeyRef = useRef<Record<string, string>>({});

  if (!isOpen) return null;

  const resetForm = () => {
    setEditingTemplate(null);
    setIsCreating(false);
    setTemplateName('');
    setTemplateItems([]);
    createKeyRef.current = null;
    updateKeyRef.current = null;
  };

  const handleStartCreate = () => {
    setIsCreating(true);
    setTemplateName('');
    setTemplateItems([{ sectionType: 'SERVICE', title: '', content: '' }]);
  };

  const handleStartEdit = (template: CareTemplate) => {
    setEditingTemplate(template);
    setTemplateName(template.name);
    // 將 CareNoteItem[] 轉為 CareNoteItemDto[] 進行編輯
    setTemplateItems(
      template.items.map((item) => ({
        sectionType: item.sectionType,
        title: item.title,
        content: item.content
      }))
    );
  };

  const handleAddItem = () => {
    setTemplateItems((prev) => [...prev, { sectionType: 'SERVICE', title: '', content: '' }]);
  };

  const handleRemoveItem = (index: number) => {
    setTemplateItems((prev) => prev.filter((_, i) => i !== index));
  };

  const handleItemChange = (index: number, field: keyof CareNoteItemDto, value: string) => {
    setTemplateItems((prev) => {
      const updated = [...prev];
      updated[index] = { ...updated[index], [field]: value };
      return updated;
    });
  };

  const handleSave = () => {
    if (!templateName.trim()) {
      alert('請輸入範本名稱');
      return;
    }
    if (templateItems.length === 0) {
      alert('範本至少需要包含一個項目');
      return;
    }

    const payload = {
      name: templateName,
      items: templateItems
    };

    if (isCreating) {
      if (!createKeyRef.current) {
        createKeyRef.current = crypto.randomUUID();
      }
      createMutation.mutate(
        { dto: payload, idempotencyKey: createKeyRef.current },
        {
          onSuccess: () => {
            resetForm();
          },
          onError: (err: any) => {
            if (err.response?.status === 409) {
              alert('請求重複提交中，請稍候');
            } else {
              alert(err.response?.data?.message || '建立範本失敗');
              createKeyRef.current = null;
            }
          }
        }
      );
    } else if (editingTemplate) {
      if (!updateKeyRef.current) {
        updateKeyRef.current = crypto.randomUUID();
      }
      updateMutation.mutate(
        { templateId: editingTemplate.id, dto: payload, idempotencyKey: updateKeyRef.current },
        {
          onSuccess: () => {
            resetForm();
          },
          onError: (err: any) => {
            if (err.response?.status === 409) {
              alert('請求重複提交中，請稍候');
            } else {
              alert(err.response?.data?.message || '更新範本失敗');
              updateKeyRef.current = null;
            }
          }
        }
      );
    }
  };

  const handleDelete = (templateId: string) => {
    if (!confirm('確認要刪除此範本嗎？')) return;

    if (!deleteKeyRef.current[templateId]) {
      deleteKeyRef.current[templateId] = crypto.randomUUID();
    }

    deleteMutation.mutate(
      { templateId, idempotencyKey: deleteKeyRef.current[templateId] },
      {
        onSuccess: () => {
          delete deleteKeyRef.current[templateId];
        },
        onError: (err: any) => {
          if (err.response?.status === 409) {
            alert('請求重複提交中，請稍候');
          } else {
            alert(err.response?.data?.message || '刪除範本失敗');
            delete deleteKeyRef.current[templateId];
          }
        }
      }
    );
  };

  return createPortal(
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 1100,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'rgba(45, 47, 46, 0.5)',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)'
      }}
      data-testid="template-manager-backdrop"
    >
      <div
        className="card-layered"
        style={{
          width: '90%',
          maxWidth: '500px',
          maxHeight: '90vh',
          display: 'flex',
          flexDirection: 'column',
          padding: '2rem',
          background: 'var(--color-surface-lowest)',
          overflow: 'hidden'
        }}
      >
        {/* Header */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
          <h2 style={{ margin: 0, fontFamily: 'var(--font-display)', color: 'var(--color-primary)' }}>
            {isCreating ? '新增範本' : editingTemplate ? '編輯範本' : '管理照護範本'}
          </h2>
          <button
            onClick={isCreating || editingTemplate ? resetForm : onClose}
            style={{
              background: 'none',
              border: 'none',
              fontSize: '1.5rem',
              cursor: 'pointer',
              color: 'var(--color-on-surface-variant)'
            }}
          >
            ×
          </button>
        </div>

        {/* Content Area */}
        <div style={{ flex: 1, overflowY: 'auto', marginBottom: '1.5rem', paddingRight: '0.5rem' }}>
          {isCreating || editingTemplate ? (
            /* Form view for Add/Edit */
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1.2rem' }}>
              <div>
                <label style={{ display: 'block', marginBottom: '0.5rem', fontWeight: 600 }}>範本名稱</label>
                <input
                  type="text"
                  value={templateName}
                  onChange={(e) => setTemplateName(e.target.value)}
                  data-testid="sitter-template-manager-name-input"
                  style={{
                    width: '100%',
                    padding: '0.8rem',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--color-surface-high)',
                    boxSizing: 'border-box'
                  }}
                  placeholder="例如：基礎餵食與散步"
                />
              </div>

              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                  <label style={{ fontWeight: 600 }}>項目明細</label>
                  <button
                    onClick={handleAddItem}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: 'var(--color-primary)',
                      cursor: 'pointer',
                      fontWeight: 600
                    }}
                  >
                    + 新增項目
                  </button>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                  {templateItems.map((item, index) => (
                    <div
                      key={index}
                      style={{
                        padding: '1rem',
                        background: 'var(--color-surface-low)',
                        borderRadius: 'var(--radius-sm)',
                        position: 'relative'
                      }}
                    >
                      <button
                        onClick={() => handleRemoveItem(index)}
                        style={{
                          position: 'absolute',
                          top: '8px',
                          right: '8px',
                          border: 'none',
                          background: 'none',
                          color: 'var(--color-error)',
                          cursor: 'pointer'
                        }}
                      >
                        移除
                      </button>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
                        <select
                          value={item.sectionType}
                          onChange={(e) => handleItemChange(index, 'sectionType', e.target.value as any)}
                          style={{
                            padding: '0.5rem',
                            borderRadius: 'var(--radius-sm)',
                            border: '1px solid var(--color-surface-high)'
                          }}
                        >
                          <option value="SERVICE">服務項目</option>
                          <option value="CONTACT">聯絡資訊</option>
                          <option value="WARNING">重要警示</option>
                          <option value="PREFERENCE">喜好與禁忌</option>
                          <option value="HOSPITAL">特約醫院</option>
                          <option value="OTHER">其他說明</option>
                        </select>

                        <input
                          type="text"
                          value={item.title}
                          onChange={(e) => handleItemChange(index, 'title', e.target.value)}
                          placeholder="項目標題 (如：餵飼料)"
                          style={{
                            padding: '0.5rem',
                            borderRadius: 'var(--radius-sm)',
                            border: '1px solid var(--color-surface-high)'
                          }}
                        />

                        <textarea
                          value={item.content}
                          onChange={(e) => handleItemChange(index, 'content', e.target.value)}
                          placeholder="細節說明..."
                          rows={2}
                          style={{
                            padding: '0.5rem',
                            borderRadius: 'var(--radius-sm)',
                            border: '1px solid var(--color-surface-high)',
                            fontFamily: 'inherit',
                            resize: 'vertical'
                          }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            /* List View */
            <div>
              {isLoading ? (
                <div style={{ textAlign: 'center', padding: '2rem' }}>載入中...</div>
              ) : templates.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '2rem', color: 'var(--color-on-surface-variant)' }}>
                  尚無範本，點擊下方新增。
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                  {templates.map((template) => (
                    <div
                      key={template.id}
                      style={{
                        padding: '1rem',
                        background: 'var(--color-surface-low)',
                        borderRadius: 'var(--radius-lg)',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center'
                      }}
                    >
                      <div>
                        <strong style={{ display: 'block', fontSize: '1.1rem' }}>{template.name}</strong>
                        <span style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)' }}>
                          條目數: {template.items?.length || 0}
                        </span>
                      </div>
                      <div style={{ display: 'flex', gap: '0.5rem' }}>
                        <button
                          onClick={() => handleStartEdit(template)}
                          data-testid={`sitter-template-edit-${template.id}`}
                          style={{
                            background: 'none',
                            border: 'none',
                            color: 'var(--color-primary)',
                            cursor: 'pointer',
                            padding: '0.5rem'
                          }}
                        >
                          編輯
                        </button>
                        <button
                          onClick={() => handleDelete(template.id)}
                          data-testid={`sitter-carenote-template-delete-${template.id}`}
                          style={{
                            background: 'none',
                            border: 'none',
                            color: 'var(--color-error)',
                            cursor: 'pointer',
                            padding: '0.5rem'
                          }}
                        >
                          刪除
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer Actions */}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem', borderTop: '1px solid var(--color-outline-variant)', paddingTop: '1rem' }}>
          {isCreating || editingTemplate ? (
            <>
              <button
                className="btn-primary"
                onClick={resetForm}
                style={{
                  background: 'none',
                  color: 'var(--color-on-surface)',
                  border: '1px solid var(--color-surface-high)'
                }}
              >
                取消
              </button>
              <button
                className="btn-primary"
                onClick={handleSave}
                data-testid="sitter-template-save-btn"
                disabled={createMutation.isPending || updateMutation.isPending}
              >
                {createMutation.isPending || updateMutation.isPending ? '儲存中...' : '儲存範本'}
              </button>
            </>
          ) : (
            <>
              <button
                className="btn-primary"
                onClick={onClose}
                style={{
                  background: 'none',
                  color: 'var(--color-on-surface)',
                  border: '1px solid var(--color-surface-high)'
                }}
              >
                關閉
              </button>
              <button className="btn-primary" onClick={handleStartCreate}>
                + 新增範本
              </button>
            </>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
};

export default TemplateManagerModal;
