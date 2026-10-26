import React, { useEffect, useRef, useState } from 'react';
import Card from '../../components/ui/Card';
import { modifyOrder, getOrderDetail } from '../../api/orderApi';
import type { OrderItemDto, PlanDateRangeDto } from '../../api/orderApi';

interface OrderModificationWizardProps {
  orderId: string;
}

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** YYYY-MM-DD 且是真實存在的日期（擋 2026-02-30） */
function isValidDate(date: string): boolean {
  if (!DATE_PATTERN.test(date)) return false;
  const d = new Date(`${date}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === date;
}

/**
 * 訂單所有方案區間的交集（每個新增日期都要同時落在每個方案區間內，與後端 ModificationService 一致）。
 * null = 該端不限；兩端都 null = 常態開放
 */
function effectiveRange(ranges: PlanDateRangeDto[]): { start: string | null; end: string | null } {
  let start: string | null = null;
  let end: string | null = null;
  for (const r of ranges) {
    if (r.startDate && (!start || r.startDate > start)) start = r.startDate;
    if (r.endDate && (!end || r.endDate < end)) end = r.endDate;
  }
  return { start, end };
}

const OrderModificationWizard: React.FC<OrderModificationWizardProps> = ({ orderId }) => {
  const [datesStr, setDatesStr] = useState('');
  const [isCancel, setIsCancel] = useState(false);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<'IDLE' | 'SUCCESS' | 'ERROR'>('IDLE');
  const [existingItems, setExistingItems] = useState<OrderItemDto[]>([]);
  const [planRanges, setPlanRanges] = useState<PlanDateRangeDto[]>([]);
  const [itemsLoading, setItemsLoading] = useState(true);
  const submitKeyRef = useRef<string | null>(null);

  useEffect(() => {
    getOrderDetail(orderId)
      .then((order) => {
        setExistingItems(order.items);
        // 舊後端沒有 planDateRanges：前端不擋，交給後端 422 PLAN_NOT_IN_RANGE
        setPlanRanges(order.planDateRanges ?? []);
        // 以原訂單日期當起點讓飼主增刪，不再預填寫死的 2026-05 日期
        const original = [...new Set(order.items.flatMap((item) => item.dates ?? []))].sort();
        setDatesStr(original.join(', '));
      })
      .catch((err) => console.error('讀取訂單原始內容失敗', err))
      .finally(() => setItemsLoading(false));
  }, [orderId]);

  // 方案可預訂日期區間 (PRD-003 B)：取自訂單方案的真實資料（2026-10-26 前寫死 2026-05-01~05-31，
  // 真實訂單的未來日期一律被擋，驗收 20261026-1517 L1）。原訂單已有的日期不檢查，與後端一致
  const { start: planStart, end: planEnd } = effectiveRange(planRanges);
  const rangeLabel =
    !planStart && !planEnd
      ? '常態開放（不限日期）'
      : `${planStart ?? '不限'} ~ ${planEnd ?? '不限'}`;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setStatus('IDLE');

    const dates = isCancel
      ? []
      : datesStr
          .split(',')
          .map((d) => d.trim())
          .filter(Boolean);

    const invalid = dates.filter((date) => !isValidDate(date));
    if (invalid.length > 0) {
      alert(`日期格式錯誤（需為 YYYY-MM-DD）：${invalid.join(', ')}`);
      setLoading(false);
      return;
    }

    // 前端防呆（UX）：新增的日期需落在方案可預訂區間；權威檢查在後端 (SD-016 §2.1)
    const originalDates = new Set(existingItems.flatMap((item) => item.dates ?? []));
    const outOfRange = dates.filter(
      (date) =>
        !originalDates.has(date) &&
        ((planStart !== null && date < planStart) || (planEnd !== null && date > planEnd))
    );
    if (outOfRange.length > 0) {
      alert(
        `錯誤 (PLAN_NOT_IN_RANGE)：${outOfRange.join(', ')} 超出方案可預訂日期區間（${rangeLabel}）`
      );
      setLoading(false);
      return;
    }

    if (!isCancel && existingItems.length === 0) {
      alert('讀取原始訂單內容失敗，請重新整理後再試');
      setLoading(false);
      return;
    }

    try {
      if (!submitKeyRef.current) {
        submitKeyRef.current = crypto.randomUUID();
      }
      // 保留原始項目的方案/單價/次數等欄位，僅替換新日期範圍，
      // 避免送出前端自創的欄位形狀導致後端反序列化時把訂單內容清空
      const items: OrderItemDto[] = isCancel
        ? []
        : existingItems.map((item) => ({
            ...item,
            dates,
            quantity: dates.length * item.timesPerDay
          }));

      await modifyOrder(
        orderId,
        'OWNER',
        {
          items,
          totalDays: dates.length,
          dates
        },
        submitKeyRef.current
      );

      submitKeyRef.current = null;
      setStatus('SUCCESS');
      alert('變更申請已成功提交！');
    } catch (err) {
      console.error(err);
      submitKeyRef.current = null;
      setStatus('ERROR');
      const data = (err as { response?: { data?: { error?: string; message?: string } } }).response
        ?.data;
      // 後端 422 PLAN_NOT_IN_RANGE 等業務錯誤帶回原因，不吞成「請重試」
      alert(
        data?.message
          ? `提交變更失敗${data.error ? ` (${data.error})` : ''}：${data.message}`
          : '提交變更失敗，請重試。'
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '600px',
        margin: '0 auto'
      }}
    >
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          fontFamily: 'var(--font-display)',
          marginBottom: '2rem'
        }}
      >
        訂單變更精靈
      </h2>

      <Card>
        {status === 'SUCCESS' ? (
          <div
            style={{ textAlign: 'center', padding: '2rem 0' }}
            data-testid="modification-success-banner"
          >
            <div style={{ fontSize: '3rem', color: 'var(--color-primary)', marginBottom: '1rem' }}>
              ✓
            </div>
            <h3 style={{ margin: 0, color: 'var(--color-primary)' }}>變更請求已送出</h3>
            <p
              style={{
                color: 'var(--color-on-surface-variant)',
                fontSize: '0.875rem',
                marginTop: '0.5rem'
              }}
            >
              保母端已收到變更提案，請靜候報價審核。
            </p>
          </div>
        ) : (
          <form
            onSubmit={handleSubmit}
            style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}
          >
            <div
              style={{
                padding: '1rem',
                backgroundColor: 'var(--color-surface-low)',
                borderRadius: 'var(--radius-sm)',
                fontSize: '0.875rem'
              }}
            >
              <strong>方案可預訂日期區間：</strong>{' '}
              <span data-testid="modify-plan-range">{itemsLoading ? '讀取中…' : rangeLabel}</span>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <input
                type="checkbox"
                id="cancel-booking"
                checked={isCancel}
                onChange={(e) => setIsCancel(e.target.checked)}
                data-testid="cancel-checkbox"
              />
              <label
                htmlFor="cancel-booking"
                style={{
                  fontSize: '0.875rem',
                  fontWeight: '700',
                  color: 'var(--color-primary)',
                  cursor: 'pointer'
                }}
              >
                申請完全取消此預約 (全額/部分退款)
              </label>
            </div>

            {!isCancel && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>
                  變更後日期 (逗號分隔)
                </label>
                <input
                  type="text"
                  value={datesStr}
                  onChange={(e) => setDatesStr(e.target.value)}
                  placeholder="YYYY-MM-DD, YYYY-MM-DD"
                  style={{
                    padding: '0.75rem',
                    borderRadius: 'var(--radius-sm)',
                    border: 'none',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'var(--color-on-surface)',
                    outline: 'none'
                  }}
                  data-testid="modify-dates-input"
                  required
                />
                <span style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                  已預填原訂單日期；新增的日期須落在上方方案可預訂日期區間內（PLAN_NOT_IN_RANGE）。
                </span>
              </div>
            )}

            <button
              type="submit"
              disabled={loading || itemsLoading}
              className="btn-primary"
              style={{ width: '100%', padding: '1rem', marginTop: '1rem' }}
              data-testid="modify-submit-btn"
            >
              {loading ? '提交中...' : isCancel ? '送出取消預約申請' : '提交變更申請'}
            </button>
          </form>
        )}
      </Card>
    </div>
  );
};

export default OrderModificationWizard;
