import React from 'react';
import { useNavigate } from 'react-router-dom';
import StatusChip from '../../components/ui/StatusChip';
import { subStatusTone } from '../../components/ui/statusTone';
import { visitStatusFullLabel } from '../../components/ui/statusLabels';
import { LoadingBlock, PageContainer, QueryErrorBlock } from '../../components/orders/common';
import { useOwnerTodayQuery } from '../../hooks/useVisits';

/** 飼主「行程」頁（SD-FULFILL §9.1 `/owner/visits`，F7）：今天的保母到府行程，點擊進訂單詳情 */
const OwnerVisitsToday: React.FC = () => {
  const navigate = useNavigate();
  const { data, isLoading, error, refetch } = useOwnerTodayQuery();
  return (
    <PageContainer>
      <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>今日行程</h2>
      {isLoading ? (
        <LoadingBlock />
      ) : error || !data ? (
        <QueryErrorBlock error={error} context="visit" onRetry={() => refetch()} />
      ) : data.today.length === 0 ? (
        <p className="muted" style={{ textAlign: 'center' }} data-testid="owner-dashboard-empty">
          今天沒有保母到府行程
        </p>
      ) : (
        data.today.map((c) => (
          <div
            key={c.visitId}
            className="card-layered stack"
            style={{ padding: '1rem', gap: '0.375rem', cursor: 'pointer' }}
            role="link"
            tabIndex={0}
            onClick={() => navigate(`/owner/orders/${c.orderId}`)}
            onKeyDown={(e) => e.key === 'Enter' && navigate(`/owner/orders/${c.orderId}`)}
            data-testid={`owner-dashboard-visit-card-${c.visitId}`}
          >
            <div
              className="btn-row"
              style={{ alignItems: 'center', justifyContent: 'space-between' }}
            >
              <strong style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{c.name}</strong>
              <StatusChip
                label={visitStatusFullLabel(c.status, c.completionSource)}
                tone={subStatusTone(c.status)}
                status={c.status}
              />
            </div>
            <span className="muted">
              {c.serviceDate}・保母 {c.counterpart?.displayName ?? '—'}
            </span>
            {(c.pets ?? []).length > 0 && (
              <span className="muted" style={{ overflowWrap: 'anywhere' }}>
                {c.pets!.map((p) => p.name).join('、')}
              </span>
            )}
          </div>
        ))
      )}
    </PageContainer>
  );
};

export default OwnerVisitsToday;
