import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Card from '../../components/ui/Card';
import { getPendingKycList, suspendSitter, unsuspendSitter } from '../../api/kycApi';
import type { PendingKycRecordDto } from '../../api/kycApi';
import { useRole } from '../../contexts/RoleContext';

const AdminKycList: React.FC = () => {
  const { currentRole } = useRole();
  const navigate = useNavigate();
  const [records, setRecords] = useState<PendingKycRecordDto[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState('');

  const [targetSitterId, setTargetSitterId] = useState('');
  const [suspendReason, setSuspendReason] = useState('');
  const [controlLoading, setControlLoading] = useState(false);
  const [controlMsg, setControlMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(
    null
  );

  useEffect(() => {
    fetchPendingList();
  }, [page]);

  const fetchPendingList = async () => {
    setLoading(true);
    setErrorMsg('');
    try {
      const data = await getPendingKycList(page, 10);
      setRecords(data.content);
      setTotalPages(data.totalPages);
    } catch (err: any) {
      console.error(err);
      setErrorMsg('取得待審核清單失敗，請確認是否具備管理員權限');
    } finally {
      setLoading(false);
    }
  };

  const handleSuspend = async (e: React.FormEvent) => {
    e.preventDefault();
    setControlMsg(null);
    if (!targetSitterId.trim()) {
      setControlMsg({ type: 'error', text: '請輸入保母 ID' });
      return;
    }
    if (!suspendReason.trim()) {
      setControlMsg({ type: 'error', text: '請輸入停權原因' });
      return;
    }

    setControlLoading(true);
    try {
      const idempotencyKey = 'suspend-' + targetSitterId + '-' + Date.now();
      await suspendSitter(targetSitterId, suspendReason, idempotencyKey);
      setControlMsg({
        type: 'success',
        text: `保母 ${targetSitterId} 已成功停權且強制關閉接單狀態。`
      });
      setSuspendReason('');
    } catch (err: any) {
      console.error(err);
      const msg = err.response?.data?.message || '停權操作失敗';
      setControlMsg({ type: 'error', text: msg });
    } finally {
      setControlLoading(false);
    }
  };

  const handleUnsuspend = async () => {
    setControlMsg(null);
    if (!targetSitterId.trim()) {
      setControlMsg({ type: 'error', text: '請輸入保母 ID' });
      return;
    }

    setControlLoading(true);
    try {
      const idempotencyKey = 'unsuspend-' + targetSitterId + '-' + Date.now();
      await unsuspendSitter(targetSitterId, idempotencyKey);
      setControlMsg({
        type: 'success',
        text: `已成功解除保母 ${targetSitterId} 停權狀態，接單資格已恢復。`
      });
    } catch (err: any) {
      console.error(err);
      const msg = err.response?.data?.message || '解除停權失敗';
      setControlMsg({ type: 'error', text: msg });
    } finally {
      setControlLoading(false);
    }
  };

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '960px',
        margin: '0 auto'
      }}
    >
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          marginBottom: '1.5rem'
        }}
      >
        管理端 - KYC 審核與資格管理
      </h2>

      {currentRole !== 'admin' && (
        <div
          style={{
            padding: '1rem',
            backgroundColor: '#f8d7da',
            color: '#721c24',
            borderRadius: '8px',
            marginBottom: '1.5rem',
            fontWeight: '600'
          }}
        >
          ⚠️ 警告：當前角色非管理員，無法調用後端 API。請在 Demo 首頁切換為管理員角色。
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: '1.5rem' }}>
        <div>
          <Card style={{ padding: '1.5rem' }}>
            <h3
              style={{
                fontSize: '1.2rem',
                fontWeight: '600',
                marginBottom: '1rem',
                color: 'var(--color-on-surface)'
              }}
            >
              待審核實名認證申請
            </h3>

            {errorMsg && (
              <div style={{ color: '#ef4444', marginBottom: '1rem', fontWeight: '500' }}>
                {errorMsg}
              </div>
            )}

            {loading ? (
              <div
                style={{
                  textAlign: 'center',
                  padding: '2rem',
                  color: 'var(--color-on-surface-variant)'
                }}
              >
                載入中...
              </div>
            ) : records.length === 0 ? (
              <div
                style={{
                  textAlign: 'center',
                  padding: '3rem',
                  color: 'var(--color-on-surface-variant)'
                }}
              >
                目前無待審核的實名認證申請。
              </div>
            ) : (
              <div>
                <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                  <thead>
                    <tr
                      style={{
                        borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
                        color: 'var(--color-on-surface-variant)'
                      }}
                    >
                      <th style={{ padding: '10px' }}>保母姓名</th>
                      <th style={{ padding: '10px' }}>Email</th>
                      <th style={{ padding: '10px' }}>提交時間</th>
                      <th style={{ padding: '10px', textAlign: 'right' }}>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {records.map((record) => (
                      <tr
                        key={record.recordId}
                        style={{ borderBottom: '1px solid rgba(255, 255, 255, 0.05)' }}
                      >
                        <td style={{ padding: '12px 10px', fontWeight: '500' }}>
                          {record.fullName}
                        </td>
                        <td style={{ padding: '12px 10px', fontSize: '0.85rem' }}>
                          {record.email}
                        </td>
                        <td style={{ padding: '12px 10px', fontSize: '0.8rem' }}>
                          {new Date(record.submittedAt).toLocaleString()}
                        </td>
                        <td style={{ padding: '12px 10px', textAlign: 'right' }}>
                          <button
                            className="btn-primary"
                            style={{ fontSize: '0.8rem', padding: '6px 12px' }}
                            onClick={() => navigate(`/admin/kyc/${record.recordId}`)}
                          >
                            進入審查
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                {totalPages > 1 && (
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'center',
                      gap: '0.5rem',
                      marginTop: '1.5rem'
                    }}
                  >
                    <button
                      disabled={page === 0}
                      onClick={() => setPage(page - 1)}
                      style={{
                        padding: '4px 8px',
                        borderRadius: '4px',
                        border: '1px solid rgba(255,255,255,0.1)',
                        background: 'transparent',
                        color: 'white',
                        cursor: 'pointer'
                      }}
                    >
                      上一頁
                    </button>
                    <span
                      style={{
                        alignSelf: 'center',
                        fontSize: '0.85rem',
                        color: 'var(--color-on-surface-variant)'
                      }}
                    >
                      第 {page + 1} 頁 / 共 {totalPages} 頁
                    </span>
                    <button
                      disabled={page >= totalPages - 1}
                      onClick={() => setPage(page + 1)}
                      style={{
                        padding: '4px 8px',
                        borderRadius: '4px',
                        border: '1px solid rgba(255,255,255,0.1)',
                        background: 'transparent',
                        color: 'white',
                        cursor: 'pointer'
                      }}
                    >
                      下一頁
                    </button>
                  </div>
                )}
              </div>
            )}
          </Card>
        </div>

        <div>
          <Card style={{ padding: '1.5rem' }}>
            <h3
              style={{
                fontSize: '1.15rem',
                fontWeight: '600',
                marginBottom: '1.25rem',
                color: 'var(--color-on-surface)'
              }}
            >
              停權與解除停權工具
            </h3>

            <form
              onSubmit={handleSuspend}
              style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}
            >
              <div>
                <label
                  style={{
                    display: 'block',
                    fontSize: '0.85rem',
                    fontWeight: '600',
                    marginBottom: '0.25rem'
                  }}
                >
                  目標保母 ID (Sitter UUID)
                </label>
                <input
                  type="text"
                  value={targetSitterId}
                  onChange={(e) => setTargetSitterId(e.target.value)}
                  placeholder="請輸入 sitterId"
                  style={{
                    width: '100%',
                    padding: '8px',
                    borderRadius: '6px',
                    border: '1px solid rgba(255,255,255,0.1)',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'white',
                    fontSize: '0.85rem'
                  }}
                />
              </div>

              <div>
                <label
                  style={{
                    display: 'block',
                    fontSize: '0.85rem',
                    fontWeight: '600',
                    marginBottom: '0.25rem'
                  }}
                >
                  停權事由 (限 1-300 字)
                </label>
                <textarea
                  value={suspendReason}
                  onChange={(e) => setSuspendReason(e.target.value.substring(0, 300))}
                  placeholder="例如：違反服務條款第七條，涉及私下交易..."
                  rows={3}
                  style={{
                    width: '100%',
                    padding: '8px',
                    borderRadius: '6px',
                    border: '1px solid rgba(255,255,255,0.1)',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'white',
                    fontSize: '0.85rem',
                    resize: 'none'
                  }}
                />
              </div>

              {controlMsg && (
                <div
                  style={{
                    fontSize: '0.85rem',
                    fontWeight: '500',
                    color: controlMsg.type === 'success' ? '#10b981' : '#ef4444'
                  }}
                >
                  {controlMsg.text}
                </div>
              )}

              <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
                <button
                  type="submit"
                  disabled={controlLoading}
                  style={{
                    flex: 1,
                    padding: '10px 0',
                    backgroundColor: '#ef4444',
                    border: 'none',
                    borderRadius: '6px',
                    color: 'white',
                    fontWeight: '600',
                    fontSize: '0.85rem',
                    cursor: 'pointer'
                  }}
                >
                  {controlLoading ? '執行中...' : '執行停權'}
                </button>
                <button
                  type="button"
                  disabled={controlLoading}
                  onClick={handleUnsuspend}
                  style={{
                    flex: 1,
                    padding: '10px 0',
                    backgroundColor: '#10b981',
                    border: 'none',
                    borderRadius: '6px',
                    color: 'white',
                    fontWeight: '600',
                    fontSize: '0.85rem',
                    cursor: 'pointer'
                  }}
                >
                  解除停權
                </button>
              </div>
            </form>
          </Card>
        </div>
      </div>
    </div>
  );
};

export default AdminKycList;
