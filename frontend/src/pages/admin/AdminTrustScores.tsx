import React, { useEffect, useState } from 'react';
import Card from '../../components/ui/Card';
import { listSitterTrustScores, adjustTrustScore } from '../../api/kycApi';
import type { SitterTrustScoreDto } from '../../api/kycApi';
import { useRole } from '../../contexts/RoleContext';

const AdminTrustScores: React.FC = () => {
  const { currentRole } = useRole();
  const [records, setRecords] = useState<SitterTrustScoreDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState('');

  const [targetSitterId, setTargetSitterId] = useState('');
  const [delta, setDelta] = useState<number>(0);
  const [reason, setReason] = useState('');
  const [adjustLoading, setAdjustLoading] = useState(false);
  const [adjustMsg, setAdjustMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(
    null
  );

  const fetchList = async () => {
    setLoading(true);
    setErrorMsg('');
    try {
      const data = await listSitterTrustScores();
      setRecords(data);
    } catch (err) {
      console.error(err);
      setErrorMsg('取得信用指標清單失敗，請確認是否具備管理員權限');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchList();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleAdjust = async (e: React.FormEvent) => {
    e.preventDefault();
    setAdjustMsg(null);
    if (!targetSitterId.trim()) {
      setAdjustMsg({ type: 'error', text: '請選擇保母' });
      return;
    }
    if (!delta) {
      setAdjustMsg({ type: 'error', text: '請輸入增減點數（不可為 0）' });
      return;
    }
    if (!reason.trim()) {
      setAdjustMsg({ type: 'error', text: '請輸入異動原因' });
      return;
    }

    setAdjustLoading(true);
    try {
      const idempotencyKey = 'trust-adjust-' + targetSitterId + '-' + Date.now();
      await adjustTrustScore(targetSitterId, delta, reason, idempotencyKey);
      setAdjustMsg({ type: 'success', text: '信用指標已成功更新' });
      setDelta(0);
      setReason('');
      await fetchList();
    } catch (err: any) {
      console.error(err);
      const msg = err.response?.data?.message || '異動失敗';
      setAdjustMsg({ type: 'error', text: msg });
    } finally {
      setAdjustLoading(false);
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
          marginBottom: '0.5rem'
        }}
      >
        管理端 - 內部信用指標管理
      </h2>
      <p style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)', marginBottom: '1.5rem' }}>
        此資訊為後台內部指標，飼主與保母在前台皆無法查看。低於 60 點標註為高風險。
      </p>

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
        <Card style={{ padding: '1.5rem' }}>
          <h3
            style={{
              fontSize: '1.2rem',
              fontWeight: '600',
              marginBottom: '1rem',
              color: 'var(--color-on-surface)'
            }}
          >
            保母信用指標清單
          </h3>

          {errorMsg && (
            <div style={{ color: '#ef4444', marginBottom: '1rem', fontWeight: '500' }}>
              {errorMsg}
            </div>
          )}

          {loading ? (
            <div style={{ textAlign: 'center', padding: '2rem', color: 'var(--color-on-surface-variant)' }}>
              載入中...
            </div>
          ) : records.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--color-on-surface-variant)' }}>
              目前沒有保母資料。
            </div>
          ) : (
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
                  <th style={{ padding: '10px', textAlign: 'right' }}>信用點數</th>
                  <th style={{ padding: '10px', textAlign: 'right' }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {records.map((r) => (
                  <tr
                    key={r.sitterId}
                    style={{ borderBottom: '1px solid rgba(255, 255, 255, 0.05)' }}
                    data-testid={`trust-row-${r.sitterId}`}
                  >
                    <td style={{ padding: '12px 10px', fontWeight: '500' }}>{r.fullName}</td>
                    <td style={{ padding: '12px 10px', fontSize: '0.85rem' }}>{r.email}</td>
                    <td style={{ padding: '12px 10px', textAlign: 'right' }}>
                      <span
                        style={{
                          fontWeight: '700',
                          color: r.highRisk ? '#dc2626' : 'var(--color-on-surface)'
                        }}
                      >
                        {r.trustScore}
                      </span>
                      {r.highRisk && (
                        <span
                          data-testid={`trust-high-risk-${r.sitterId}`}
                          style={{
                            marginLeft: '8px',
                            fontSize: '0.7rem',
                            fontWeight: '700',
                            color: '#dc2626',
                            backgroundColor: '#fee2e2',
                            padding: '2px 8px',
                            borderRadius: '9999px'
                          }}
                        >
                          高風險
                        </span>
                      )}
                    </td>
                    <td style={{ padding: '12px 10px', textAlign: 'right' }}>
                      <button
                        className="btn-primary"
                        style={{ fontSize: '0.8rem', padding: '6px 12px' }}
                        onClick={() => setTargetSitterId(r.sitterId)}
                        data-testid={`trust-select-${r.sitterId}`}
                      >
                        選取調整
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card style={{ padding: '1.5rem', height: 'fit-content' }}>
          <h3
            style={{
              fontSize: '1.2rem',
              fontWeight: '600',
              marginBottom: '1rem',
              color: 'var(--color-on-surface)'
            }}
          >
            異動信用點數
          </h3>
          <form onSubmit={handleAdjust} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
              <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>保母 ID</label>
              <input
                type="text"
                value={targetSitterId}
                onChange={(e) => setTargetSitterId(e.target.value)}
                placeholder="從左側清單選取，或直接輸入"
                style={{ padding: '0.6rem', borderRadius: '8px', border: '1px solid var(--color-outline-variant)' }}
                data-testid="trust-adjust-sitter-id"
              />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
              <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>增減點數（負數為扣分）</label>
              <input
                type="number"
                value={delta}
                onChange={(e) => setDelta(Number(e.target.value))}
                style={{ padding: '0.6rem', borderRadius: '8px', border: '1px solid var(--color-outline-variant)' }}
                data-testid="trust-adjust-delta"
              />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
              <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>異動原因</label>
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={3}
                style={{
                  padding: '0.6rem',
                  borderRadius: '8px',
                  border: '1px solid var(--color-outline-variant)',
                  resize: 'none'
                }}
                data-testid="trust-adjust-reason"
              />
            </div>

            {adjustMsg && (
              <div
                style={{
                  fontSize: '0.8rem',
                  fontWeight: '600',
                  color: adjustMsg.type === 'success' ? '#16a34a' : '#dc2626'
                }}
                data-testid="trust-adjust-message"
              >
                {adjustMsg.text}
              </div>
            )}

            <button
              type="submit"
              className="btn-primary"
              disabled={adjustLoading || currentRole !== 'admin'}
              style={{ padding: '0.75rem' }}
              data-testid="trust-adjust-submit-btn"
            >
              {adjustLoading ? '送出中...' : '確認異動'}
            </button>
          </form>
        </Card>
      </div>
    </div>
  );
};

export default AdminTrustScores;
