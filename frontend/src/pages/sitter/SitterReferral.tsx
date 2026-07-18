import React, { useEffect, useState } from 'react';
import Card from '../../components/ui/Card';
import { getReferralCandidates, createReferral } from '../../api/trustCircleApi';
import type { ReferralCandidate } from '../../api/trustCircleApi';

interface SitterReferralProps {
  ownerId: string;
  orderId?: string;
}

const SitterReferral: React.FC<SitterReferralProps> = ({ ownerId, orderId }) => {
  const [candidates, setCandidates] = useState<ReferralCandidate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [message, setMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    getReferralCandidates(ownerId)
      .then(setCandidates)
      .catch((err) => {
        console.error(err);
        setError('取得信任圈候選名單失敗');
      })
      .finally(() => setLoading(false));
  }, [ownerId]);

  const toggleSelect = (sitterId: string) => {
    setSelectedIds((prev) => (prev.includes(sitterId) ? prev.filter((id) => id !== sitterId) : [...prev, sitterId]));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitError(null);
    if (selectedIds.length === 0) {
      setSubmitError('請至少選擇一位推薦對象');
      return;
    }

    setSubmitting(true);
    try {
      await createReferral({ orderId, ownerId, recommendedSitterIds: selectedIds, message });
      setSubmitted(true);
    } catch (err: any) {
      setSubmitError(err.response?.data?.message || '轉介失敗，請稍後再試');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '640px', margin: '0 auto' }}>
      <h2 style={{ fontSize: '1.75rem', fontWeight: '700', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)', marginBottom: '0.5rem' }}>
        不接單並轉介
      </h2>
      <p style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)', marginBottom: '1.5rem' }}>
        從信任圈中選擇推薦對象，飼主與被推薦保母都會收到系統通知。
      </p>

      {submitted ? (
        <Card style={{ textAlign: 'center', padding: '2rem' }} data-testid="referral-success-banner">
          <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>✓</div>
          <div style={{ fontWeight: '700' }}>轉介已送出</div>
        </Card>
      ) : loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>載入中...</div>
      ) : error ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }}>{error}</div>
      ) : candidates.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>
          信任圈內沒有可推薦的對象（可能尚未建立信任關係，或對象已將此飼主列入黑名單）
        </div>
      ) : (
        <form onSubmit={handleSubmit}>
          <Card style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }} data-testid="referral-candidate-list">
              {candidates.map((c) => (
                <label
                  key={c.sitterId}
                  style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.9rem', opacity: c.available ? 1 : 0.5 }}
                  data-testid={`referral-candidate-${c.sitterId}`}
                >
                  <input
                    type="checkbox"
                    checked={selectedIds.includes(c.sitterId)}
                    onChange={() => toggleSelect(c.sitterId)}
                    data-testid={`referral-candidate-checkbox-${c.sitterId}`}
                  />
                  {c.displayName}
                  {!c.available && <span style={{ fontSize: '0.75rem', color: '#dc2626' }}>（目前停權/休息中）</span>}
                </label>
              ))}
            </div>
          </Card>

          <Card style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
            <label style={{ fontSize: '0.8rem', fontWeight: '600', display: 'block', marginBottom: '0.5rem' }}>推薦語</label>
            <textarea
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              rows={3}
              placeholder="例如：這位保母也很細心，特別擅長照顧老貓"
              style={{ width: '100%', padding: '0.6rem', borderRadius: '8px', border: '1px solid var(--color-outline-variant)', resize: 'none', boxSizing: 'border-box' }}
              data-testid="referral-message-input"
            />
          </Card>

          {submitError && <div style={{ color: '#dc2626', fontSize: '0.85rem', marginBottom: '1rem' }} data-testid="referral-error">{submitError}</div>}

          <button type="submit" className="btn-primary" disabled={submitting} style={{ width: '100%', padding: '0.9rem' }} data-testid="referral-submit-btn">
            {submitting ? '送出中...' : '送出轉介'}
          </button>
        </form>
      )}
    </div>
  );
};

export default SitterReferral;
