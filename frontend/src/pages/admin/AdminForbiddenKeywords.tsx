import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import Button from '../../components/ui/Button';
import {
  useForbiddenKeywordsQuery,
  useAddForbiddenKeywordMutation,
  useDeleteForbiddenKeywordMutation
} from '../../hooks/usePublicProfile';
import { Trash2, Search, Plus, AlertCircle, CheckCircle2 } from 'lucide-react';

const AdminForbiddenKeywords: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [page, setPage] = useState(0);
  const [newKeyword, setNewKeyword] = useState('');
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const { data, isLoading, error, refetch } = useForbiddenKeywordsQuery(searchQuery, page, 10);
  const addMutation = useAddForbiddenKeywordMutation();
  const deleteMutation = useDeleteForbiddenKeywordMutation();

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    refetch();
  };

  const handleAddKeyword = async (e: React.FormEvent) => {
    e.preventDefault();
    setMessage(null);
    const cleanWord = newKeyword.trim();
    if (!cleanWord) return;

    try {
      await addMutation.mutateAsync(cleanWord);
      setMessage({ type: 'success', text: `成功新增敏感詞: "${cleanWord}"` });
      setNewKeyword('');
      refetch();
    } catch (err: any) {
      console.error(err);
      const errMsg = err.response?.data?.message || '新增敏感詞失敗，可能已存在';
      setMessage({ type: 'error', text: errMsg });
    }
  };

  const handleDeleteKeyword = async (id: string, keyword: string) => {
    if (!window.confirm(`確定要刪除敏感詞 "${keyword}" 嗎？`)) return;
    setMessage(null);

    try {
      await deleteMutation.mutateAsync(id);
      setMessage({ type: 'success', text: `敏感詞 "${keyword}" 已成功刪除` });
      refetch();
    } catch (err: any) {
      console.error(err);
      setMessage({ type: 'error', text: '刪除敏感詞失敗' });
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '800px', margin: '0 auto' }}>
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          fontFamily: 'var(--font-display)',
          marginBottom: '0.5rem'
        }}
      >
        系統敏感詞管理 (Admin)
      </h2>
      <p style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', marginBottom: '2rem' }}>
        管理全站保母公開檔案（暱稱、自我介紹）過濾用的敏感詞彙。保母更新檔案時，若包含此處的字詞將被阻擋。
      </p>

      {/* 新增敏感詞 */}
      <Card style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
        <form onSubmit={handleAddKeyword} style={{ display: 'flex', gap: '12px', alignItems: 'flex-end' }}>
          <div style={{ flex: 1 }}>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              新增敏感詞 (上限 50 字)
            </label>
            <input
              type="text"
              value={newKeyword}
              onChange={(e) => setNewKeyword(e.target.value)}
              placeholder="例如 買賣"
              maxLength={50}
              style={{
                width: '100%',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)',
                boxSizing: 'border-box'
              }}
              data-testid="admin-keyword-input-add"
            />
          </div>
          <Button
            type="submit"
            disabled={addMutation.isPending}
            className="btn-primary"
            style={{ padding: '0.75rem 1.5rem', display: 'flex', alignItems: 'center', gap: '8px', height: '45px' }}
            data-testid="admin-keyword-btn-add"
          >
            <Plus size={18} /> {addMutation.isPending ? '新增中...' : '新增'}
          </Button>
        </form>
      </Card>

      {/* 查詢與列表 */}
      <Card style={{ padding: '1.5rem' }}>
        <form onSubmit={handleSearch} style={{ display: 'flex', gap: '12px', marginBottom: '1.5rem' }}>
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="搜尋敏感詞..."
            style={{
              flex: 1,
              padding: '0.75rem',
              borderRadius: '8px',
              border: '1px solid var(--color-surface-high)',
              backgroundColor: 'var(--color-surface)',
              color: 'var(--color-on-surface)',
              fontSize: '0.875rem'
            }}
            data-testid="admin-keyword-input-search"
          />
          <Button
            type="submit"
            variant="secondary"
            style={{ padding: '0.75rem 1.5rem', display: 'flex', alignItems: 'center', gap: '8px' }}
          >
            <Search size={18} /> 搜尋
          </Button>
        </form>

        {message && (
          <div
            style={{
              padding: '0.75rem',
              borderRadius: '6px',
              fontSize: '0.875rem',
              fontWeight: '500',
              marginBottom: '1.5rem',
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              backgroundColor: message.type === 'success' ? '#dcfce7' : '#fee2e2',
              color: message.type === 'success' ? '#16a34a' : '#ef4444'
            }}
            data-testid="admin-keyword-message"
          >
            {message.type === 'success' ? <CheckCircle2 size={18} /> : <AlertCircle size={18} />}
            {message.text}
          </div>
        )}

        {isLoading ? (
          <div style={{ textAlign: 'center', padding: '2rem 0', color: 'var(--color-on-surface-variant)' }}>
            載入中...
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: '2rem 0', color: 'var(--color-error)' }}>
            載入敏感詞失敗
          </div>
        ) : !data || data.content.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '2rem 0', color: 'var(--color-on-surface-variant)' }}>
            無任何敏感詞紀錄
          </div>
        ) : (
          <div>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ borderBottom: '2px solid var(--color-surface-high)' }}>
                  <th style={{ padding: '0.75rem 0.5rem', color: 'var(--color-on-surface-variant)' }}>敏感詞</th>
                  <th style={{ padding: '0.75rem 0.5rem', color: 'var(--color-on-surface-variant)' }}>新增時間</th>
                  <th style={{ padding: '0.75rem 0.5rem', textAlign: 'right', color: 'var(--color-on-surface-variant)' }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((item) => (
                  <tr key={item.id} style={{ borderBottom: '1px solid var(--color-surface-high)' }}>
                    <td style={{ padding: '1rem 0.5rem', fontWeight: '600', color: 'var(--color-on-surface)' }}>
                      {item.keyword}
                    </td>
                    <td style={{ padding: '1rem 0.5rem', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
                      {new Date(item.createdAt).toLocaleString()}
                    </td>
                    <td style={{ padding: '1rem 0.5rem', textAlign: 'right' }}>
                      <button
                        onClick={() => handleDeleteKeyword(item.id, item.keyword)}
                        style={{
                          background: 'none',
                          border: 'none',
                          color: 'var(--color-error)',
                          cursor: 'pointer',
                          padding: '4px'
                        }}
                        data-testid={`admin-keyword-btn-delete-${item.id}`}
                      >
                        <Trash2 size={18} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* 分頁控制 */}
            {data.totalPages > 1 && (
              <div style={{ display: 'flex', justifyContent: 'center', gap: '8px', marginTop: '1.5rem' }}>
                <Button
                  variant="secondary"
                  disabled={page === 0}
                  onClick={() => setPage(page - 1)}
                  style={{ padding: '0.5rem 1rem' }}
                >
                  上一頁
                </Button>
                <span style={{ alignSelf: 'center', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
                  頁碼 {page + 1} / {data.totalPages}
                </span>
                <Button
                  variant="secondary"
                  disabled={page >= data.totalPages - 1}
                  onClick={() => setPage(page + 1)}
                  style={{ padding: '0.5rem 1rem' }}
                >
                  下一頁
                </Button>
              </div>
            )}
          </div>
        )}
      </Card>
    </div>
  );
};

export default AdminForbiddenKeywords;
