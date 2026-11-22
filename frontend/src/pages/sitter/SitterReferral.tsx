import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import Button from '../../components/ui/Button';
import {
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import { STATUS_TONE_STYLE, SUCCESS_TEXT_COLOR } from '../../components/ui/statusTone';
import { getReferralCandidates, createReferral } from '../../api/trustCircleApi';

interface SitterReferralProps {
  ownerId: string;
  /** 有值 = 婉拒後轉介（PRD-027 BR-3 第二步，SD-010 §1.3 B）；無值 = 非訂單關聯轉介（SD-010 §1.3 D） */
  orderId?: string;
}

/** PRD-027 EX-16：轉介失敗不回滾婉拒，一律顯示同一文案、可重試（不讀後端 message） */
const REFERRAL_FAILED = '轉介未送出，請稍後再試';

/**
 * 轉介信任圈夥伴（PRD-010 B、PRD-027 BR-3 / EX-16，SD-010 §1.2–1.3）。
 * 婉拒（O3）成立後，由婉拒對話框的 `sitter-order-decline-btn-referral` 帶 `?orderId=` 進入；此時只送 `orderId`，
 * 飼主由後端從訂單反查。轉介是獨立請求，失敗不影響已成立的婉拒。
 */
const SitterReferral: React.FC<SitterReferralProps> = ({ ownerId, orderId }) => {
  const candidatesQuery = useQuery({
    queryKey: ['referral-candidates', ownerId],
    queryFn: () => getReferralCandidates(ownerId),
    enabled: !!ownerId
  });
  const candidates = candidatesQuery.data ?? [];

  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [message, setMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const toggleSelect = (sitterId: string) => {
    setSelectedIds((prev) =>
      prev.includes(sitterId) ? prev.filter((id) => id !== sitterId) : [...prev, sitterId]
    );
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitError(null);
    if (selectedIds.length === 0) {
      setSubmitError('請至少選擇一位推薦對象');
      return;
    }
    setSubmitting(true);
    try {
      await createReferral({
        ...(orderId ? { orderId } : { ownerId }),
        recommendedSitterIds: selectedIds,
        message
      });
      setSubmitted(true);
    } catch {
      setSubmitError(REFERRAL_FAILED);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PageContainer testId="sitter-referral-page">
      <div className="stack" style={{ gap: '0.375rem' }}>
        <h2 style={{ margin: 0 }}>轉介信任圈夥伴</h2>
        <p className="muted" style={{ margin: 0 }}>
          {orderId ? '已婉拒此預約。' : ''}
          從信任圈中選擇推薦對象，飼主與被推薦保母都會收到系統通知。
        </p>
      </div>

      {submitted ? (
        <SectionCard testId="referral-success-banner">
          <p role="status" style={{ margin: 0, fontWeight: 700, color: SUCCESS_TEXT_COLOR }}>
            轉介已送出
          </p>
        </SectionCard>
      ) : candidatesQuery.isLoading ? (
        <LoadingBlock />
      ) : candidatesQuery.error ? (
        <QueryErrorBlock error={candidatesQuery.error} onRetry={() => candidatesQuery.refetch()} />
      ) : candidates.length === 0 ? (
        <p className="muted" data-testid="sitter-referral-empty">
          信任圈內沒有可推薦的對象（可能尚未建立信任關係，或對象已將此飼主列入黑名單）
        </p>
      ) : (
        <form onSubmit={handleSubmit} className="stack">
          <SectionCard title="推薦對象">
            <div className="stack" style={{ gap: '0.5rem' }} data-testid="referral-candidate-list">
              {candidates.map((c) => (
                <label
                  key={c.sitterId}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '0.5rem',
                    flexWrap: 'wrap'
                  }}
                  data-testid={`referral-candidate-${c.sitterId}`}
                >
                  <input
                    type="checkbox"
                    checked={selectedIds.includes(c.sitterId)}
                    onChange={() => toggleSelect(c.sitterId)}
                    data-testid={`referral-candidate-checkbox-${c.sitterId}`}
                  />
                  <span style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{c.displayName}</span>
                  {!c.available && (
                    <span
                      style={{
                        ...STATUS_TONE_STYLE.warning,
                        fontSize: '0.75rem',
                        padding: '0.125rem 0.5rem',
                        borderRadius: '9999px',
                        whiteSpace: 'nowrap',
                        flexShrink: 0
                      }}
                    >
                      目前停權 / 休息中
                    </span>
                  )}
                </label>
              ))}
            </div>
          </SectionCard>

          <SectionCard>
            <div>
              <label className="field-label" htmlFor="referral-message">
                推薦語
              </label>
              <textarea
                id="referral-message"
                className="form-input"
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                rows={3}
                placeholder="例如：這位保母也很細心，特別擅長照顧老貓"
                style={{ resize: 'none' }}
                data-testid="referral-message-input"
              />
            </div>
          </SectionCard>

          {submitError && (
            <p role="alert" className="field-error" data-testid="referral-error">
              {submitError}
            </p>
          )}

          <Button
            type="submit"
            className="btn-primary"
            fullWidth
            disabled={submitting}
            data-testid="referral-submit-btn"
          >
            {submitting ? '送出中…' : '送出轉介'}
          </Button>
        </form>
      )}
    </PageContainer>
  );
};

export default SitterReferral;
