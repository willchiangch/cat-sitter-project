import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import Card from '../../components/ui/Card';
import { getPendingKycList, suspendSitter, unsuspendSitter } from '../../api/kycApi';
import type { PendingKycRecordDto } from '../../api/kycApi';
import { useRole } from '../../contexts/RoleContext';
import { SUCCESS_TEXT_COLOR } from '../../components/ui/statusTone';

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
  const suspendKeyRef = useRef<string | null>(null);
  const unsuspendKeyRef = useRef<string | null>(null);

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
      if (!suspendKeyRef.current) {
        suspendKeyRef.current = 'suspend-' + targetSitterId + '-' + crypto.randomUUID();
      }
      await suspendSitter(targetSitterId, suspendReason, suspendKeyRef.current);
      suspendKeyRef.current = null;
      setControlMsg({
        type: 'success',
        text: `保母 ${targetSitterId} 已成功停權且強制關閉接單狀態。`
      });
      setSuspendReason('');
    } catch (err: any) {
      console.error(err);
      suspendKeyRef.current = null;
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
      if (!unsuspendKeyRef.current) {
        unsuspendKeyRef.current = 'unsuspend-' + targetSitterId + '-' + crypto.randomUUID();
      }
      await unsuspendSitter(targetSitterId, unsuspendKeyRef.current);
      unsuspendKeyRef.current = null;
      setControlMsg({
        type: 'success',
        text: `已成功解除保母 ${targetSitterId} 停權狀態，接單資格已恢復。`
      });
    } catch (err: any) {
      console.error(err);
      unsuspendKeyRef.current = null;
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

      {/* 390px 行動版殼內只有單欄可用：過去 '1fr 320px' 讓停權工具卡整張落在殼外、待審清單被擠成
          一字一行（驗收 N1）。minmax(0, 1fr) 避免欄寬被表格 min-content 撐開 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr)', gap: '1.5rem' }}>
        <div>
          <Card style={{ padding: '1.5rem' }} data-testid="admin-kyc-pending-card">
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
              <div style={{ color: 'var(--color-error)', marginBottom: '1rem', fontWeight: '500' }}>
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
                  padding: '3rem 1rem',
                  color: 'var(--color-on-surface-variant)'
                }}
                data-testid="admin-kyc-empty"
              >
                目前無待審核的實名認證申請。
              </div>
            ) : (
              <div>
                {/* 四欄表格在 294px 的卡片內每欄只剩一兩個字寬（驗收 N1），改成一筆一列的堆疊清單 */}
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {records.map((record) => (
                    <li
                      key={record.recordId}
                      data-testid="admin-kyc-row"
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '0.75rem',
                        padding: '12px 0',
                        borderBottom: '1px solid var(--color-surface-high)'
                      }}
                    >
                      <div style={{ flex: 1, minWidth: 0, overflowWrap: 'anywhere' }}>
                        <div style={{ fontWeight: '600' }}>{record.fullName}</div>
                        <div
                          style={{
                            fontSize: '0.85rem',
                            color: 'var(--color-on-surface-variant)'
                          }}
                        >
                          {record.email}
                        </div>
                        <div
                          style={{
                            fontSize: '0.8rem',
                            color: 'var(--color-on-surface-variant)'
                          }}
                        >
                          提交時間：{new Date(record.submittedAt).toLocaleString()}
                        </div>
                      </div>
                      <button
                        className="btn-primary"
                        style={{
                          flexShrink: 0,
                          whiteSpace: 'nowrap',
                          fontSize: '0.8rem',
                          padding: '6px 12px'
                        }}
                        data-testid="admin-kyc-review-btn"
                        onClick={() => navigate(`/admin/kyc/${record.recordId}`)}
                      >
                        進入審查
                      </button>
                    </li>
                  ))}
                </ul>

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
          <Card style={{ padding: '1.5rem' }} data-testid="admin-kyc-suspend-card">
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
                  data-testid="admin-kyc-suspend-sitter-id-input"
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
                  data-testid="admin-kyc-suspend-reason-input"
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
                    color: controlMsg.type === 'success' ? SUCCESS_TEXT_COLOR : 'var(--color-error)'
                  }}
                >
                  {controlMsg.text}
                </div>
              )}

              <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
                <button
                  type="submit"
                  disabled={controlLoading}
                  data-testid="admin-kyc-suspend-btn"
                  style={{
                    flex: 1,
                    padding: '10px 0',
                    // fix-batch13 K9：白字配 #ef4444 只有 3.76:1，改 --color-error #b02500 + --color-on-error（6.7:1）
                    backgroundColor: 'var(--color-error)',
                    border: 'none',
                    borderRadius: '6px',
                    color: 'var(--color-on-error)',
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
                  data-testid="admin-kyc-unsuspend-btn"
                  style={{
                    flex: 1,
                    padding: '10px 0',
                    // fix-batch13 K9：白字配 #10b981 只有 2.54:1，改 #047857（5.48:1）
                    backgroundColor: SUCCESS_TEXT_COLOR,
                    border: 'none',
                    borderRadius: '6px',
                    color: '#ffffff',
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
