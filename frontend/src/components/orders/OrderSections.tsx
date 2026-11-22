import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import StatusChip from '../ui/StatusChip';
import { subStatusTone } from '../ui/statusTone';
import {
  modificationStatusLabel,
  modificationTypeLabel,
  partyLabel,
  visitStatusFullLabel
} from '../ui/statusLabels';
import { formatMoney } from '../../utils/money';
import { formatDateTime } from '../../utils/datetime';
import type { OrderDetail, OrderGroup } from '../../api/orderApi';
import { useModificationsQuery } from '../../hooks/useOrders';
import { LoadingBlock, QueryErrorBlock, SectionCard } from './common';
import { useQuery } from '@tanstack/react-query';
import { listVisits } from '../../api/fulfillApi';
import type { VisitRow } from '../../api/fulfillApi';
import { orderKeys } from '../../hooks/useOrders';

/** 預約內容：群組 × 方案 × 日期 × 毛孩（含已刪除標示，PRD-006 EX-23） */
export const OrderGroupsView: React.FC<{ groups?: OrderGroup[]; petTestIdPrefix?: string }> = ({
  groups,
  petTestIdPrefix
}) => (
  <SectionCard title="預約內容">
    {(groups ?? []).map((g) => (
      <div key={g.orderItemId} className="surface-block stack" style={{ gap: '0.375rem' }}>
        <div className="kv-row">
          <span>{g.planTitle}</span>
          <span>
            {g.unitPrice !== undefined ? formatMoney(g.unitPrice) : ''} × 每日 {g.dailyCount} 次
          </span>
        </div>
        <span className="muted" style={{ overflowWrap: 'anywhere' }}>
          {g.dates.join('、')}
        </span>
        <div className="btn-row">
          {g.pets.map((p) => (
            <span
              key={p.petId}
              data-testid={petTestIdPrefix ? `${petTestIdPrefix}${p.petId}` : undefined}
              style={{ fontSize: '0.875rem' }}
            >
              {p.name}
              {p.deleted && (
                <span
                  className="muted"
                  data-testid={
                    petTestIdPrefix ? `sitter-order-eval-pet-deleted-label-${p.petId}` : undefined
                  }
                >
                  （已刪除）
                </span>
              )}
            </span>
          ))}
        </div>
      </div>
    ))}
  </SectionCard>
);

/** 報價明細三段（PRD-006 BR-16、SD-ORDER E2 `quote`；雙方頁面共用 testid） */
export const QuoteBreakdown: React.FC<{ detail: OrderDetail }> = ({ detail }) => {
  const q = detail.quote;
  if (!q) {
    return (
      <SectionCard title="試算金額" testId="owner-order-detail-quote-breakdown">
        <div className="kv-row">
          <span>預約試算</span>
          <span>{formatMoney(detail.baseAmount)}</span>
        </div>
        <p className="muted" style={{ margin: 0 }}>
          最終總額由保母評估後確認
        </p>
      </SectionCard>
    );
  }
  const o = q.original;
  const originalTotal =
    o.totalAmount ?? o.baseAmount + (o.surchargeAmount ?? 0) - (o.discountAmount ?? 0);
  return (
    <SectionCard title="報價明細" testId="owner-order-detail-quote-breakdown">
      <div className="kv-row">
        <span>原始試算</span>
        <span>{formatMoney(o.baseAmount)}</span>
      </div>
      {!!o.surchargeAmount && (
        <div className="kv-row">
          <span>加價</span>
          <span>{formatMoney(o.surchargeAmount)}</span>
        </div>
      )}
      {!!o.discountAmount && (
        <div className="kv-row">
          <span>折扣</span>
          <span>{formatMoney(-o.discountAmount)}</span>
        </div>
      )}
      {o.pricingNotes && (
        <p className="muted" style={{ margin: 0, overflowWrap: 'anywhere' }}>
          調整原因：{o.pricingNotes}
        </p>
      )}
      <div className="kv-row">
        <span>原報價總額</span>
        <span data-testid="order-detail-quote-original-total">{formatMoney(originalTotal)}</span>
      </div>
      {(q.adjustments ?? []).map((a) => (
        <div
          key={`${a.modificationId}-${a.kind}`}
          className="surface-block stack"
          style={{ gap: '0.25rem' }}
          data-testid={`order-detail-quote-modification-${a.modificationId}`}
        >
          <div className="kv-row">
            <span>
              {a.kind === 'SUPPLEMENT_EXPIRED' ? '補款逾時調整' : '訂單變更'}
              {a.effectiveAt ? `（${formatDateTime(a.effectiveAt)}）` : ''}
            </span>
            <span>{formatMoney(a.amountChange)}</span>
          </div>
          {a.removedVisitDates && a.removedVisitDates.length > 0 && (
            <span className="muted" style={{ overflowWrap: 'anywhere' }}>
              取消行程：{a.removedVisitDates.join('、')}
            </span>
          )}
          {a.addedItems && a.addedItems.length > 0 && (
            <span className="muted" style={{ overflowWrap: 'anywhere' }}>
              新增行程：
              {a.addedItems.map((i) => `${i.date} ${i.planTitle} × ${i.dailyCount}`).join('、')}
            </span>
          )}
        </div>
      ))}
      <div className="kv-row" style={{ fontSize: '1rem' }}>
        <span>目前總額</span>
        <span data-testid="order-detail-quote-current-total">
          {formatMoney(q.totalAmount ?? originalTotal)}
        </span>
      </div>
    </SectionCard>
  );
};

/** 變更歷史（E18；PRD-016 §4.6）；每筆可點進協商頁唯讀明細 */
export const ModificationHistory: React.FC<{ orderId: string }> = ({ orderId }) => {
  const { data, isLoading, error, refetch } = useModificationsQuery(orderId);
  return (
    <SectionCard title="變更紀錄">
      {isLoading ? (
        <LoadingBlock />
      ) : error ? (
        <QueryErrorBlock error={error} context="modification" onRetry={() => refetch()} />
      ) : !data || data.length === 0 ? (
        <p className="muted" style={{ margin: 0 }} data-testid="modification-history-empty">
          尚無變更紀錄
        </p>
      ) : (
        data.map((m) => (
          <Link
            key={m.modificationId}
            to={`/orders/${orderId}/modifications/${m.modificationId}`}
            className="surface-block stack"
            style={{ gap: '0.25rem', textDecoration: 'none', color: 'inherit' }}
            data-testid={`modification-history-item-${m.modificationId}`}
          >
            <div
              className="btn-row"
              style={{ alignItems: 'center', justifyContent: 'space-between' }}
            >
              <span style={{ fontWeight: 600 }}>
                {modificationTypeLabel(m.type)}（{partyLabel(m.initiatedBy)}提出）
              </span>
              <StatusChip
                label={modificationStatusLabel(m.status)}
                tone={subStatusTone(m.status)}
                status={m.status}
              />
            </div>
            <span className="muted">{formatDateTime(m.createdAt)}</span>
            {m.reason && (
              <span className="muted" style={{ overflowWrap: 'anywhere' }}>
                原因：{m.reason}
              </span>
            )}
          </Link>
        ))
      )}
    </SectionCard>
  );
};

/**
 * 行程區塊（SD-FULFILL §9.1，F1）：
 * - OWNER / ADMIN：`PENDING` / `PENDING_PAYMENT` / `CANCELLED` 不顯示；可展開檢核項；日誌已送出才給「查看日誌」
 * - SITTER：任何訂單狀態都顯示；`CONFIRMED` 之後點擊進入服務面板
 */
export const OrderVisitList: React.FC<{
  orderId: string;
  orderStatus: string;
  viewer: 'OWNER' | 'SITTER' | 'ADMIN';
}> = ({ orderId, orderStatus, viewer }) => {
  const navigate = useNavigate();
  const hiddenForOwner = ['PENDING', 'PENDING_PAYMENT', 'CANCELLED'].includes(orderStatus);
  const enabled = viewer === 'SITTER' || !hiddenForOwner;
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: orderKeys.visits(orderId),
    queryFn: () => listVisits(orderId),
    enabled,
    staleTime: 0
  });
  const [expanded, setExpanded] = React.useState<string | null>(null);
  if (!enabled) return null;
  const panelEnabled = ['CONFIRMED', 'IN_PROGRESS', 'DISPUTED', 'COMPLETED'].includes(orderStatus);

  const row = (visit: VisitRow) => {
    const status = visitStatusFullLabel(visit.status, visit.completionSource);
    if (viewer === 'SITTER') {
      return (
        <div
          key={visit.visitId}
          className="surface-block stack"
          style={{ gap: '0.25rem', cursor: panelEnabled ? 'pointer' : 'default' }}
          onClick={() =>
            panelEnabled && navigate(`/sitter/orders/${orderId}/visits/${visit.visitId}`)
          }
          role={panelEnabled ? 'link' : undefined}
        >
          <span
            style={{ fontWeight: 600, overflowWrap: 'anywhere' }}
            data-testid={`sitter-order-visit-label-${visit.visitId}`}
          >
            {visit.name}
          </span>
          <div className="btn-row" style={{ alignItems: 'center', gap: '0.5rem' }}>
            <span
              style={{ whiteSpace: 'nowrap' }}
              data-testid={`sitter-order-visit-date-${visit.visitId}`}
            >
              {visit.serviceDate}
            </span>
            <span
              className="muted"
              style={{ whiteSpace: 'nowrap', fontSize: '0.75rem' }}
              data-testid={`sitter-order-visit-status-${visit.visitId}`}
            >
              {status}
            </span>
          </div>
        </div>
      );
    }
    const p = viewer === 'OWNER' ? 'owner-order-detail' : 'admin-order-detail';
    const items = visit.checklist?.items ?? [];
    return (
      <div
        key={visit.visitId}
        className="surface-block stack"
        style={{ gap: '0.375rem' }}
        data-testid={`${p}-visit-row-${visit.visitId}`}
      >
        <div className="btn-row" style={{ alignItems: 'center', justifyContent: 'space-between' }}>
          <span style={{ fontWeight: 600, minWidth: 0, overflowWrap: 'anywhere' }}>
            {visit.serviceDate} {visit.name}
          </span>
          <StatusChip
            label={status}
            tone={subStatusTone(visit.status)}
            status={visit.status}
            testId={`${p}-visit-status-${visit.visitId}`}
          />
        </div>
        {visit.startedAt && (
          <span className="muted" data-testid={`${p}-visit-started-at-${visit.visitId}`}>
            開始 {formatDateTime(visit.startedAt)}
          </span>
        )}
        {visit.completedAt && (
          <span className="muted" data-testid={`${p}-visit-completed-at-${visit.visitId}`}>
            結束 {formatDateTime(visit.completedAt)}
          </span>
        )}
        {visit.completionSource === 'SYSTEM' && (
          <span className="muted" data-testid={`${p}-visit-auto-completed-${visit.visitId}`}>
            系統自動結束
          </span>
        )}
        <div className="btn-row">
          <button
            type="button"
            onClick={() => setExpanded(expanded === visit.visitId ? null : visit.visitId)}
            style={{
              background: 'none',
              border: 'none',
              padding: 0,
              color: 'var(--color-primary)',
              cursor: 'pointer',
              fontSize: '0.875rem'
            }}
          >
            {expanded === visit.visitId
              ? '收合檢核項'
              : `檢核項 ${visit.checklist?.completed ?? 0}/${visit.checklist?.total ?? 0}`}
          </button>
          {visit.careLog?.submitted && (
            <Link
              to={`/orders/${orderId}/visits/${visit.visitId}/care-log`}
              data-testid={`${p}-btn-view-log-${visit.visitId}`}
              style={{ color: 'var(--color-primary)', fontSize: '0.875rem' }}
            >
              查看日誌
            </Link>
          )}
        </div>
        {expanded === visit.visitId && (
          <ul
            data-testid={`${p}-visit-checklist-${visit.visitId}`}
            style={{ margin: 0, paddingLeft: '1.25rem', fontSize: '0.875rem' }}
          >
            {items.length === 0 ? (
              <li className="muted">保母未設定檢核項</li>
            ) : (
              items.map((it) => (
                <li
                  key={it.itemId}
                  data-testid={`${p}-visit-checklist-item-${it.itemId}`}
                  data-checked={it.isCompleted}
                  style={{ overflowWrap: 'anywhere' }}
                >
                  {it.isCompleted ? '✓ ' : '○ '}
                  {it.title}
                </li>
              ))
            )}
          </ul>
        )}
      </div>
    );
  };

  return (
    <SectionCard title="行程">
      {isLoading ? (
        <LoadingBlock />
      ) : error ? (
        <QueryErrorBlock error={error} context="visit" onRetry={() => refetch()} />
      ) : !data || data.visits.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>
          尚無行程
        </p>
      ) : (
        data.visits.map(row)
      )}
    </SectionCard>
  );
};
