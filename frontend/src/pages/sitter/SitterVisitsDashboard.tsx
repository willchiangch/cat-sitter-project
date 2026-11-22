import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import StatusChip from '../../components/ui/StatusChip';
import { subStatusTone } from '../../components/ui/statusTone';
import { visitStatusFullLabel } from '../../components/ui/statusLabels';
import {
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import { useSitterDashboardQuery } from '../../hooks/useVisits';
import { useCurrentUser } from '../../hooks/useCurrentUser';
import type { DashboardCard } from '../../api/fulfillApi';
import { formatDateTime } from '../../utils/datetime';

const VisitCard: React.FC<{ card: DashboardCard; overdue: boolean; sitterId: string }> = ({
  card,
  overdue,
  sitterId
}) => {
  const navigate = useNavigate();
  return (
    <div
      className="surface-block stack"
      style={{ gap: '0.375rem' }}
      data-testid={`sitter-dashboard-visit-card-${card.visitId}`}
    >
      <div className="btn-row" style={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <strong style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{card.name}</strong>
        <StatusChip
          label={visitStatusFullLabel(card.status, card.completionSource)}
          tone={subStatusTone(card.status)}
          status={card.status}
          testId={`sitter-dashboard-visit-status-${card.visitId}`}
        />
      </div>
      <span className="muted">
        {card.serviceDate}・{card.counterpart?.displayName ?? '—'}
      </span>
      {(card.pets ?? []).length > 0 && (
        <span className="muted" style={{ overflowWrap: 'anywhere' }}>
          {card.pets!.map((p) => p.name).join('、')}
        </span>
      )}
      {overdue &&
        card.status === 'PENDING' &&
        (card.zombieDeadlinePassed ? (
          <span
            className="field-error"
            style={{ margin: 0 }}
            data-testid={`sitter-dashboard-visit-deadline-passed-${card.visitId}`}
          >
            已超過可開始期限，系統將自動關閉
          </span>
        ) : (
          card.zombieAt && (
            <span className="muted">系統將於 {formatDateTime(card.zombieAt)} 關閉</span>
          )
        ))}
      <div className="btn-row" style={{ alignItems: 'center' }}>
        <Button
          className="btn-primary"
          style={{ padding: '0.5rem 1rem' }}
          onClick={() => navigate(`/sitter/orders/${card.orderId}/visits/${card.visitId}`)}
          data-testid={`sitter-dashboard-visit-btn-open-${card.visitId}`}
        >
          進入服務面板
        </Button>
        {card.counterpart && (
          <Link
            to={`/care-notes/manage/${sitterId}/${card.counterpart.userId}`}
            style={{ color: 'var(--color-primary)', fontSize: '0.875rem' }}
            data-testid={`sitter-dashboard-visit-link-pet-notes-${card.visitId}`}
          >
            注意事項
          </Link>
        )}
      </div>
    </div>
  );
};

/** 保母「行程」頁（SD-FULFILL §9.1 `/sitter/visits`，F6）：今日行程 + 待處理（為空不顯示） */
const SitterVisitsDashboard: React.FC = () => {
  const me = useCurrentUser();
  const { data, isLoading, error, refetch } = useSitterDashboardQuery();
  return (
    <PageContainer>
      <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>行程</h2>
      {isLoading ? (
        <LoadingBlock />
      ) : error || !data ? (
        <QueryErrorBlock error={error} context="visit" onRetry={() => refetch()} />
      ) : data.today.length === 0 && data.overdue.length === 0 ? (
        <p className="muted" style={{ textAlign: 'center' }} data-testid="sitter-dashboard-empty">
          今天沒有行程
        </p>
      ) : (
        <>
          <SectionCard title="今日行程" testId="sitter-dashboard-section-today">
            {data.today.length === 0 ? (
              <span className="muted">今天沒有行程</span>
            ) : (
              data.today.map((c) => (
                <VisitCard key={c.visitId} card={c} overdue={false} sitterId={me.userId} />
              ))
            )}
          </SectionCard>
          {data.overdue.length > 0 && (
            <SectionCard title="待處理" testId="sitter-dashboard-section-overdue">
              {data.overdue.map((c) => (
                <VisitCard key={c.visitId} card={c} overdue sitterId={me.userId} />
              ))}
            </SectionCard>
          )}
        </>
      )}
    </PageContainer>
  );
};

export default SitterVisitsDashboard;
