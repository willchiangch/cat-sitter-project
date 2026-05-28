import React, { useState } from 'react';

interface OrderDisputeModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (category: string, description: string) => Promise<void>;
}

const OrderDisputeModal: React.FC<OrderDisputeModalProps> = ({ isOpen, onClose, onSubmit }) => {
  const [category, setCategory] = useState('服務品質不符');
  const [description, setDescription] = useState('');
  const [loading, setLoading] = useState(false);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!description.trim()) {
      alert('請輸入具體描述');
      return;
    }
    setLoading(true);
    try {
      await onSubmit(category, description);
      onClose();
    } catch (err) {
      console.error(err);
      alert('送出失敗，請重試');
    } finally {
      setLoading(false);
    }
  };

  const categories = ['服務品質不符', '未照約定打卡', '保母無故缺席', '其他問題'];

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.4)',
        backdropFilter: 'blur(8px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
        padding: '1.5rem'
      }}
    >
      <div
        style={{
          backgroundColor: 'var(--color-surface-high)',
          borderRadius: 'var(--radius-lg)',
          boxShadow: 'var(--shadow-lg)',
          width: '100%',
          maxWidth: '480px',
          padding: '2.5rem 2rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1.5rem'
        }}
        data-testid="dispute-modal"
      >
        <div>
          <h3
            style={{
              margin: 0,
              fontSize: '1.5rem',
              fontWeight: '700',
              color: 'var(--color-on-surface)',
              fontFamily: 'var(--font-display)'
            }}
          >
            回報訂單爭議
          </h3>
          <p
            style={{
              margin: '0.5rem 0 0 0',
              fontSize: '0.875rem',
              color: 'var(--color-on-surface-variant)'
            }}
          >
            請選擇爭議類型並填寫詳細說明，管理員將在收到申請後介入調解。
          </p>
        </div>

        <form
          onSubmit={handleSubmit}
          style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <label
              style={{ fontSize: '0.875rem', fontWeight: '600', color: 'var(--color-on-surface)' }}
            >
              爭議類別
            </label>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
              {categories.map((cat) => (
                <button
                  type="button"
                  key={cat}
                  onClick={() => setCategory(cat)}
                  style={{
                    padding: '0.5rem 1rem',
                    borderRadius: '9999px',
                    border: 'none',
                    backgroundColor:
                      category === cat
                        ? 'var(--color-primary-container)'
                        : 'var(--color-surface-low)',
                    color:
                      category === cat ? 'var(--color-primary)' : 'var(--color-on-surface-variant)',
                    fontSize: '0.8125rem',
                    fontWeight: '600',
                    cursor: 'pointer',
                    transition: 'all 0.2s'
                  }}
                  data-testid={`dispute-cat-${cat}`}
                >
                  {cat}
                </button>
              ))}
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <label
              style={{ fontSize: '0.875rem', fontWeight: '600', color: 'var(--color-on-surface)' }}
            >
              具體狀況說明
            </label>
            <textarea
              rows={4}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="請詳細敘述發生的狀況，以便加速管理員調解作業。"
              style={{
                width: '100%',
                padding: '0.75rem 1rem',
                borderRadius: 'var(--radius-sm)',
                border: 'none',
                backgroundColor: 'var(--color-surface-low)',
                color: 'var(--color-on-surface)',
                fontSize: '0.875rem',
                outline: 'none',
                resize: 'none',
                boxSizing: 'border-box'
              }}
              data-testid="dispute-desc-textarea"
            />
          </div>

          <div style={{ display: 'flex', gap: '1rem', marginTop: '1rem' }}>
            <button
              type="button"
              onClick={onClose}
              disabled={loading}
              className="btn-primary"
              style={{
                flex: 1,
                backgroundColor: 'var(--color-surface-high)',
                color: 'var(--color-on-surface-variant)',
                boxShadow: 'none'
              }}
            >
              取消
            </button>
            <button
              type="submit"
              disabled={loading}
              className="btn-primary"
              style={{ flex: 1 }}
              data-testid="dispute-submit-btn"
            >
              {loading ? '送出中...' : '確認提出'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default OrderDisputeModal;
