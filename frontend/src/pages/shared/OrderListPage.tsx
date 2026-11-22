import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import StatusBadge from '../../components/ui/StatusBadge';
import { paymentStatusLabel } from '../../components/ui/statusLabels';
import { formatMoney } from '../../utils/money';
import { formatDateTime } from '../../utils/datetime';
import { useOrderListQuery } from '../../hooks/useOrders';
import type { OrderCardDto, OrderTab } from '../../api/orderApi';
import { LoadingBlock, PageContainer, QueryErrorBlock } from '../../components/orders/common';

const TABS: { key: OrderTab; label: string; id: string }[] = [
  { key: 'EVALUATING', label: '評估中', id: 'evaluating' },
  { key: 'ACTIVE', label: '進行中', id: 'active' },
  { key: 'HISTORY', label: '歷史', id: 'history' }
];

/** 近 12 個月（Asia/Taipei）供歷史月份篩選 */
const recentMonths = (): string[] => {
  const now = new Date();
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Taipei',
    year: 'numeric',
    month: '2-digit'
  });
  const [y, m] = fmt.format(now).split('-').map(Number);
  return Array.from({ length: 12 }, (_, i) => {
    const d = new Date(Date.UTC(y, m - 1 - i, 1));
    return d.toISOString().slice(0, 7);
  });
};

const OrderCard: React.FC<{ order: OrderCardDto; role: 'owner' | 'sitter'; history: boolean }> = ({
  order,
  role,
  history
}) => {
  const navigate = useNavigate();
  const amount = history ? order.netPaid : (order.totalAmount ?? order.baseAmount);
  return (
    <div
      className="card-layered stack"
      style={{ padding: '1.25rem', cursor: 'pointer', gap: '0.5rem' }}
      role="link"
      tabIndex={0}
      onClick={() => navigate(`/${role}/orders/${order.orderId}`)}
      onKeyDown={(e) => e.key === 'Enter' && navigate(`/${role}/orders/${order.orderId}`)}
      data-testid={`${role}-order-card-${order.orderId}`}
    >
      <div className="btn-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 600, minWidth: 0, overflowWrap: 'anywhere' }}>
          {order.counterpartName ?? '—'}
        </span>
        <StatusBadge status={order.status} />
      </div>
      <span className="muted">
        {order.firstServiceDate}
        {order.lastServiceDate && order.lastServiceDate !== order.firstServiceDate
          ? ` ~ ${order.lastServiceDate}`
          : ''}
      </span>
      <div className="kv-row">
        <span>{history ? '已收淨額' : order.totalAmount !== undefined ? '總額' : '試算'}</span>
        <span>{formatMoney(amount ?? 0)}</span>
      </div>
      {order.hasOpenModification && (
        <span className="muted" style={{ color: 'var(--color-primary)' }}>
          有進行中的變更
        </span>
      )}
      {order.status === 'PENDING_PAYMENT' && order.initialPaymentStatus && (
        <span className="muted">付款：{paymentStatusLabel(order.initialPaymentStatus)}</span>
      )}
      {history && order.terminalAt && (
        <span className="muted">結束於 {formatDateTime(order.terminalAt)}</span>
      )}
    </div>
  );
};

/**
 * 訂單列表（SD-ORDER §9.1 E3 / E4、SD-FULFILL §9.2 歷史分頁）：每批 20 筆「載入更多」；
 * 歷史分頁可依狀態與月份篩選（只對飼主，PRD-009 BR-23）。
 */
const OrderListPage: React.FC<{ role: 'owner' | 'sitter' }> = ({ role }) => {
  const [tab, setTab] = useState<OrderTab>('EVALUATING');
  const [statusFilter, setStatusFilter] = useState<'' | 'COMPLETED' | 'CANCELLED'>('');
  const [month, setMonth] = useState('');
  const history = tab === 'HISTORY';
  const filtersActive = history && role === 'owner' && (!!statusFilter || !!month);
  const query = useOrderListQuery(role, {
    tab,
    ...(history && role === 'owner' && statusFilter ? { status: statusFilter } : {}),
    ...(history && role === 'owner' && month ? { month } : {})
  });
  const orders = query.data?.pages.flatMap((p) => p.content) ?? [];

  const emptyText = history ? (filtersActive ? '沒有符合條件的訂單' : '尚無歷史訂單') : '尚無訂單';
  const emptyTestId =
    history && role === 'owner' ? 'owner-orders-history-empty' : `${role}-orders-empty`;
  const loadMoreTestId =
    history && role === 'owner'
      ? 'owner-orders-history-btn-load-more'
      : `${role}-orders-btn-load-more`;

  return (
    <PageContainer>
      <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>我的訂單</h2>
      <div className="btn-row" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
            data-testid={`${role}-orders-tab-${t.id}`}
            style={{
              padding: '0.5rem 1rem',
              borderRadius: '9999px',
              border: 'none',
              whiteSpace: 'nowrap',
              background:
                tab === t.key ? 'var(--color-primary-container)' : 'var(--color-surface-low)',
              color:
                tab === t.key
                  ? 'var(--color-on-primary-container)'
                  : 'var(--color-on-surface-variant)',
              fontWeight: 600,
              cursor: 'pointer'
            }}
          >
            {t.label}
          </button>
        ))}
      </div>

      {history && role === 'owner' && (
        <div className="btn-row">
          <div style={{ flex: '1 1 8rem', minWidth: 0 }}>
            <label className="field-label" htmlFor="history-status">
              狀態
            </label>
            <select
              id="history-status"
              className="form-input"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as '' | 'COMPLETED' | 'CANCELLED')}
              data-testid="owner-orders-history-filter-status"
            >
              <option value="">全部</option>
              <option value="COMPLETED">已結案</option>
              <option value="CANCELLED">已取消</option>
            </select>
          </div>
          <div style={{ flex: '1 1 8rem', minWidth: 0 }}>
            <label className="field-label" htmlFor="history-month">
              月份
            </label>
            <select
              id="history-month"
              className="form-input"
              value={month}
              onChange={(e) => setMonth(e.target.value)}
              data-testid="owner-orders-history-select-month"
            >
              <option value="">全部月份</option>
              {recentMonths().map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          </div>
        </div>
      )}

      {query.isLoading ? (
        <LoadingBlock />
      ) : query.error ? (
        <QueryErrorBlock error={query.error} onRetry={() => query.refetch()} />
      ) : orders.length === 0 ? (
        <p
          className="muted"
          style={{ textAlign: 'center', padding: '2rem 0' }}
          data-testid={emptyTestId}
        >
          {emptyText}
        </p>
      ) : (
        <>
          {orders.map((o) => (
            <OrderCard key={o.orderId} order={o} role={role} history={history} />
          ))}
          {query.hasNextPage && (
            <div className="btn-row" style={{ justifyContent: 'center' }}>
              <Button
                variant="secondary"
                onClick={() => query.fetchNextPage()}
                disabled={query.isFetchingNextPage}
                data-testid={loadMoreTestId}
              >
                {query.isFetchingNextPage ? '載入中…' : '載入更多'}
              </Button>
            </div>
          )}
        </>
      )}
    </PageContainer>
  );
};

export default OrderListPage;
