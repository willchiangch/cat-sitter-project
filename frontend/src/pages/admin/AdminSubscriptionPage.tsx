import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import Button from '../../components/ui/Button';
import axiosClient from '../../api/axiosClient';
import { AlertCircle, CheckCircle2, Search } from 'lucide-react';

const PLAN_TIERS = ['FREE', 'BASIC', 'PRO', 'ULTIMATE'] as const;
type PlanTier = (typeof PLAN_TIERS)[number];

const PLAN_LABELS: Record<PlanTier, string> = {
  FREE: 'Free (免費版)',
  BASIC: 'Basic (基礎版)',
  PRO: 'Pro (專業版)',
  ULTIMATE: 'Ultimate (頂級版)',
};

interface SubscriptionInfo {
  sitterId: string;
  planTier: PlanTier;
  expiredAt: string | null;
  monthlyOrderCount: number;
}

const AdminSubscriptionPage: React.FC = () => {
  const [sitterId, setSitterId] = useState('');
  const [currentSub, setCurrentSub] = useState<SubscriptionInfo | null>(null);
  const [planTier, setPlanTier] = useState<PlanTier>('FREE');
  const [expiredAt, setExpiredAt] = useState('');
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const handleLookup = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!sitterId.trim()) return;
    setMessage(null);
    setLoading(true);
    try {
      const res = await axiosClient.get<SubscriptionInfo>(`/admin/subscriptions/${sitterId.trim()}`);
      setCurrentSub(res.data);
      setPlanTier(res.data.planTier);
      if (res.data.expiredAt) {
        setExpiredAt(res.data.expiredAt.substring(0, 10));
      } else {
        setExpiredAt('');
      }
    } catch (err: any) {
      const msg = err.response?.data?.message || err.response?.status === 404 ? '找不到該保母 ID' : '查詢失敗';
      setMessage({ type: 'error', text: msg });
      setCurrentSub(null);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!currentSub) return;
    setMessage(null);
    setSubmitting(true);
    try {
      const body: Record<string, string> = { planTier };
      if (expiredAt) {
        body.expiredAt = `${expiredAt}T00:00:00Z`;
      }
      if (reason.trim()) {
        body.reason = reason.trim();
      }
      const res = await axiosClient.post<SubscriptionInfo>(
        `/admin/subscriptions/${currentSub.sitterId}`,
        body
      );
      setCurrentSub(res.data);
      setMessage({ type: 'success', text: `已成功將方案更新為 ${PLAN_LABELS[res.data.planTier]}，審計日誌已寫入。` });
    } catch (err: any) {
      const msg = err.response?.data?.message || '更新失敗';
      setMessage({ type: 'error', text: msg });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '640px', margin: '0 auto' }}>
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          fontFamily: 'var(--font-display)',
          marginBottom: '0.5rem',
        }}
      >
        訂閱方案管理 (Admin)
      </h2>
      <p style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', marginBottom: '2rem' }}>
        人工覆寫保母的 SaaS 訂閱等級與到期日。所有操作均記錄於審計日誌。
      </p>

      {/* 查詢保母 */}
      <Card style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
        <form onSubmit={handleLookup} style={{ display: 'flex', gap: '12px', alignItems: 'flex-end' }}>
          <div style={{ flex: 1 }}>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)',
              }}
            >
              保母 User ID (UUID)
            </label>
            <input
              type="text"
              value={sitterId}
              onChange={(e) => setSitterId(e.target.value)}
              placeholder="e.g. 3d498178-14c0-4376-b81e-7fb02e615dda"
              data-testid="admin-sub-input-sitter-id"
              style={{
                width: '100%',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)',
                boxSizing: 'border-box',
                fontSize: '0.875rem',
              }}
            />
          </div>
          <Button
            type="submit"
            disabled={loading}
            className="btn-primary"
            style={{ padding: '0.75rem 1.25rem', display: 'flex', alignItems: 'center', gap: '6px', height: '45px' }}
            data-testid="admin-sub-btn-lookup"
          >
            <Search size={16} /> {loading ? '查詢中...' : '查詢'}
          </Button>
        </form>
      </Card>

      {/* 當前方案資訊 */}
      {currentSub && (
        <Card style={{ marginBottom: '1.5rem', padding: '1.5rem', backgroundColor: 'var(--color-surface-low)' }}>
          <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
            目前方案：
            <strong style={{ color: 'var(--color-on-surface)', marginLeft: '4px' }}>
              {PLAN_LABELS[currentSub.planTier]}
            </strong>
            {currentSub.expiredAt && (
              <>
                　到期日：
                <strong style={{ color: 'var(--color-on-surface)' }}>
                  {new Date(currentSub.expiredAt).toLocaleDateString()}
                </strong>
              </>
            )}
            {!currentSub.expiredAt && currentSub.planTier !== 'FREE' && (
              <span style={{ marginLeft: '8px', color: 'var(--color-warning)' }}>（無到期日）</span>
            )}
          </p>
          <p style={{ margin: '4px 0 0 0', fontSize: '0.8rem', color: 'var(--color-on-surface-variant)' }}>
            本月接單數：{currentSub.monthlyOrderCount}
          </p>
        </Card>
      )}

      {/* 設定表單 */}
      {currentSub && (
        <Card style={{ padding: '1.5rem' }}>
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
            <div>
              <label
                style={{
                  display: 'block',
                  fontSize: '0.875rem',
                  fontWeight: '600',
                  marginBottom: '0.5rem',
                  color: 'var(--color-on-surface)',
                }}
              >
                新方案等級
              </label>
              <select
                value={planTier}
                onChange={(e) => setPlanTier(e.target.value as PlanTier)}
                data-testid="admin-sub-select-plan"
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  borderRadius: '8px',
                  border: '1px solid var(--color-surface-high)',
                  backgroundColor: 'var(--color-surface)',
                  color: 'var(--color-on-surface)',
                  fontSize: '0.875rem',
                }}
              >
                {PLAN_TIERS.map((t) => (
                  <option key={t} value={t}>
                    {PLAN_LABELS[t]}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label
                style={{
                  display: 'block',
                  fontSize: '0.875rem',
                  fontWeight: '600',
                  marginBottom: '0.5rem',
                  color: 'var(--color-on-surface)',
                }}
              >
                到期日（選填，留空表示永不到期）
              </label>
              <input
                type="date"
                value={expiredAt}
                onChange={(e) => setExpiredAt(e.target.value)}
                data-testid="admin-sub-input-expired-at"
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  borderRadius: '8px',
                  border: '1px solid var(--color-surface-high)',
                  backgroundColor: 'var(--color-surface)',
                  color: 'var(--color-on-surface)',
                  boxSizing: 'border-box',
                  fontSize: '0.875rem',
                }}
              />
            </div>

            <div>
              <label
                style={{
                  display: 'block',
                  fontSize: '0.875rem',
                  fontWeight: '600',
                  marginBottom: '0.5rem',
                  color: 'var(--color-on-surface)',
                }}
              >
                異動原因（選填，僅供內部記錄）
              </label>
              <input
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. Close Beta 早鳥優惠、補償方案"
                maxLength={200}
                data-testid="admin-sub-input-reason"
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  borderRadius: '8px',
                  border: '1px solid var(--color-surface-high)',
                  backgroundColor: 'var(--color-surface)',
                  color: 'var(--color-on-surface)',
                  boxSizing: 'border-box',
                  fontSize: '0.875rem',
                }}
              />
            </div>

            {message && (
              <div
                style={{
                  padding: '0.75rem',
                  borderRadius: '6px',
                  fontSize: '0.875rem',
                  fontWeight: '500',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  backgroundColor: message.type === 'success' ? '#dcfce7' : '#fee2e2',
                  color: message.type === 'success' ? '#16a34a' : '#ef4444',
                }}
                data-testid="admin-sub-message"
              >
                {message.type === 'success' ? <CheckCircle2 size={18} /> : <AlertCircle size={18} />}
                {message.text}
              </div>
            )}

            <Button
              type="submit"
              disabled={submitting}
              className="btn-primary"
              style={{ padding: '0.875rem', fontSize: '1rem' }}
              data-testid="admin-sub-btn-save"
            >
              {submitting ? '更新中...' : '確認覆寫方案'}
            </Button>
          </form>
        </Card>
      )}
    </div>
  );
};

export default AdminSubscriptionPage;
