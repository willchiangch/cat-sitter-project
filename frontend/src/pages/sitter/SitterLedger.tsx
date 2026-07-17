import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import { useSitterLedgerQuery } from '../../hooks/useOrders';

const formatCurrency = (amount: number) => `$ ${amount.toLocaleString()}`;

const formatDate = (iso?: string) => {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('zh-TW', { year: 'numeric', month: '2-digit', day: '2-digit' });
};

const currentYearMonth = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
};

const shiftMonth = (yearMonth: string, delta: number) => {
  const [year, month] = yearMonth.split('-').map(Number);
  const date = new Date(year, month - 1 + delta, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
};

const SitterLedger: React.FC = () => {
  const [month, setMonth] = useState(currentYearMonth());
  const { data, isLoading, error } = useSitterLedgerQuery(month);

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '640px',
        margin: '0 auto'
      }}
    >
      <h2
        style={{
          fontSize: '2rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          fontFamily: 'var(--font-display)',
          margin: '0 0 2rem'
        }}
      >
        帳務總覽
      </h2>

      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: '1rem',
          marginBottom: '1.5rem'
        }}
      >
        <button
          onClick={() => setMonth((m) => shiftMonth(m, -1))}
          data-testid="ledger-prev-month-btn"
          style={{
            padding: '0.5rem 1rem',
            borderRadius: '8px',
            border: '1px solid var(--color-outline-variant)',
            backgroundColor: 'var(--color-surface-low)',
            cursor: 'pointer'
          }}
        >
          ← 上個月
        </button>
        <span
          data-testid="ledger-current-month"
          style={{ fontSize: '1.1rem', fontWeight: '700', minWidth: '90px', textAlign: 'center' }}
        >
          {month}
        </span>
        <button
          onClick={() => setMonth((m) => shiftMonth(m, 1))}
          data-testid="ledger-next-month-btn"
          style={{
            padding: '0.5rem 1rem',
            borderRadius: '8px',
            border: '1px solid var(--color-outline-variant)',
            backgroundColor: 'var(--color-surface-low)',
            cursor: 'pointer'
          }}
        >
          下個月 →
        </button>
      </div>

      <Card style={{ textAlign: 'center', marginBottom: '2rem' }}>
        <div style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', marginBottom: '0.5rem' }}>
          本月總收入 (依結案日統計)
        </div>
        <div
          data-testid="ledger-total-revenue"
          style={{ fontSize: '2.25rem', fontWeight: '800', color: 'var(--color-primary)' }}
        >
          {formatCurrency(data?.totalRevenue ?? 0)}
        </div>
      </Card>

      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>載入中...</div>
      ) : error ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }}>載入失敗，請稍後再試</div>
      ) : data && data.entries.length > 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }} data-testid="ledger-entry-list">
          {data.entries.map((entry) => (
            <Card key={entry.orderId} data-testid={`ledger-entry-${entry.orderId}`}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
                <span style={{ fontWeight: '700' }}>{entry.ownerName}</span>
                <span style={{ fontWeight: '700', color: 'var(--color-primary)' }}>
                  {formatCurrency(entry.totalAmount)}
                </span>
              </div>
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '0.25rem',
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)'
                }}
              >
                <div>付款日：{formatDate(entry.paidAt)}</div>
                <div>結案日：{formatDate(entry.completedAt)}</div>
                <div>預計撥款日：{formatDate(entry.payoutAt)}</div>
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>本月尚無已結案訂單</div>
      )}
    </div>
  );
};

export default SitterLedger;
