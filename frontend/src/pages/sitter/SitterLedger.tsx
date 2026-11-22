import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import StatusBadge from '../../components/ui/StatusBadge';
import { LoadingBlock, PageContainer, QueryErrorBlock } from '../../components/orders/common';
import { useSitterLedgerQuery } from '../../hooks/useOrders';
import { formatMoney } from '../../utils/money';
import { formatDateTime, toTaipeiMonth } from '../../utils/datetime';

const shiftMonth = (ym: string, delta: number) => {
  const [y, m] = ym.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1 + delta, 1)).toISOString().slice(0, 7);
};

/**
 * 保母帳務（SD-ORDER E30、SD-FULFILL §9.2）：首次以瀏覽器 Asia/Taipei 月份查詢，回應 `serverMonth` 不同時
 * 改選 `serverMonth` 並重查一次（PRD-009 AC-3.4）。收入 = 該月進入終態訂單的已收淨額。
 */
const SitterLedger: React.FC = () => {
  const navigate = useNavigate();
  const browserMonth = useMemo(() => toTaipeiMonth(new Date()), []);
  const [picked, setPicked] = useState<string | null>(null);
  // 先以瀏覽器月份查一次；回應 serverMonth 不同時預設改用 serverMonth（同 key 由 React Query 快取，不重複呼叫）
  const probe = useSitterLedgerQuery(browserMonth);
  const month = picked ?? probe.data?.serverMonth ?? browserMonth;
  const setMonth = (m: string) => setPicked(m);
  const { data, isLoading, error, refetch } = useSitterLedgerQuery(month);

  return (
    <PageContainer>
      <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>收款</h2>
      <div className="btn-row" style={{ alignItems: 'flex-end', gap: '0.75rem' }}>
        <Button
          variant="secondary"
          onClick={() => setMonth(shiftMonth(month, -1))}
          style={{ padding: '0.5rem 0.875rem', whiteSpace: 'nowrap', flexShrink: 0 }}
          data-testid="ledger-prev-month-btn"
        >
          ← 上個月
        </Button>
        <div style={{ flex: '1 1 8rem', minWidth: 0 }}>
          <label className="field-label" htmlFor="ledger-month">
            月份
          </label>
          <input
            id="ledger-month"
            type="month"
            className="form-input"
            value={month}
            onChange={(e) => e.target.value && setMonth(e.target.value)}
            data-testid="sitter-ledger-select-month"
          />
        </div>
        <Button
          variant="secondary"
          onClick={() => setMonth(shiftMonth(month, 1))}
          style={{ padding: '0.5rem 0.875rem', whiteSpace: 'nowrap', flexShrink: 0 }}
          data-testid="ledger-next-month-btn"
        >
          下個月 →
        </Button>
      </div>

      {isLoading ? (
        <LoadingBlock />
      ) : error || !data ? (
        <QueryErrorBlock error={error} onRetry={() => refetch()} />
      ) : (
        <>
          <section className="card-layered stack" style={{ padding: '1.25rem' }}>
            <span className="muted">本月收入</span>
            <span
              style={{ fontSize: '1.75rem', fontWeight: 700 }}
              data-testid="sitter-ledger-total-income"
            >
              {formatMoney(data.totalIncome)}
            </span>
          </section>
          {data.orders.length === 0 ? (
            <p className="muted" style={{ textAlign: 'center' }} data-testid="sitter-ledger-empty">
              本月沒有已結束的訂單
            </p>
          ) : (
            data.orders.map((o) => (
              <div
                key={o.orderId}
                className="card-layered stack"
                style={{ padding: '1rem', cursor: 'pointer', gap: '0.375rem' }}
                role="link"
                tabIndex={0}
                onClick={() => navigate(`/sitter/orders/${o.orderId}`)}
                onKeyDown={(e) => e.key === 'Enter' && navigate(`/sitter/orders/${o.orderId}`)}
                data-testid={`sitter-ledger-item-${o.orderId}`}
              >
                <div
                  className="btn-row"
                  style={{ justifyContent: 'space-between', alignItems: 'center' }}
                >
                  <strong>#{o.orderId.slice(0, 8).toUpperCase()}</strong>
                  <StatusBadge status={o.status} />
                </div>
                <span className="muted" style={{ overflowWrap: 'anywhere' }}>
                  {o.ownerDisplayName ?? '—'}・{o.firstServiceDate ?? ''}
                  {o.lastServiceDate && o.lastServiceDate !== o.firstServiceDate
                    ? ` ~ ${o.lastServiceDate}`
                    : ''}
                </span>
                <div className="kv-row">
                  <span>結束於 {formatDateTime(o.terminalAt)}</span>
                  <span>{formatMoney(o.netPaid)}</span>
                </div>
              </div>
            ))
          )}
        </>
      )}
    </PageContainer>
  );
};

export default SitterLedger;
