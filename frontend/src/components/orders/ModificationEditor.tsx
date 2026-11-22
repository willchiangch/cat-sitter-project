import React from 'react';
import Button from '../ui/Button';
import { visitStatusLabel } from '../ui/statusLabels';
import type { ModificationAddItem, OrderDetail } from '../../api/orderApi';
import { addDays, toTaipeiDate } from '../../utils/datetime';
import { formatMoney } from '../../utils/money';
import { orderPlansAndPets, type ModificationDraft } from './modificationDraft';

/**
 * 變更內容編輯器（PRD-016 §4.1-1、§4.3-2）：勾選要減少的 PENDING 行程、新增行程（日期 ≥ 明天、
 * 方案在本訂單內、每日 1–3 次、毛孩預設全選本訂單毛孩）。日期以 E2 `serverTime` 換算 Asia/Taipei。
 */
const ModificationEditor: React.FC<{
  detail: OrderDetail;
  draft: ModificationDraft;
  onChange: (d: ModificationDraft) => void;
  disabled?: boolean;
}> = ({ detail, draft, onChange, disabled }) => {
  const { plans, pets } = orderPlansAndPets(detail);
  const planTitle = new Map(plans.map((p) => [p.planId, p.title]));
  const pendingVisits = (detail.visits ?? []).filter((v) => v.status === 'PENDING');
  const today = toTaipeiDate(detail.serverTime);
  const minDate = addDays(today, 1);
  const maxDate = addDays(today, 365);

  const toggleVisit = (id: string) =>
    onChange({
      ...draft,
      removeVisitIds: draft.removeVisitIds.includes(id)
        ? draft.removeVisitIds.filter((x) => x !== id)
        : [...draft.removeVisitIds, id]
    });

  const updateItem = (idx: number, patch: Partial<ModificationAddItem>) =>
    onChange({
      ...draft,
      addItems: draft.addItems.map((it, i) => (i === idx ? { ...it, ...patch } : it))
    });

  const addItem = () =>
    onChange({
      ...draft,
      addItems: [
        ...draft.addItems,
        {
          date: '',
          planId: plans[0]?.planId ?? '',
          dailyCount: 1,
          petIds: pets.map((p) => p.petId)
        }
      ]
    });

  return (
    <div className="stack">
      <div className="stack" style={{ gap: '0.5rem' }}>
        <strong style={{ fontSize: '0.875rem' }}>減少行程（只能選待執行行程）</strong>
        {pendingVisits.length === 0 ? (
          <span className="muted">沒有可減少的行程</span>
        ) : (
          pendingVisits.map((v) => (
            <label key={v.visitId} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
              <input
                type="checkbox"
                disabled={disabled}
                checked={draft.removeVisitIds.includes(v.visitId)}
                onChange={() => toggleVisit(v.visitId)}
                data-testid={`order-modify-visit-checkbox-${v.visitId}`}
              />
              <span style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                {v.serviceDate} {planTitle.get(v.planId) ?? ''}（{visitStatusLabel(v.status)}）
              </span>
            </label>
          ))
        )}
      </div>

      <div className="stack" style={{ gap: '0.5rem' }}>
        <strong style={{ fontSize: '0.875rem' }}>新增行程</strong>
        {draft.addItems.map((it, idx) => {
          const plan = plans.find((p) => p.planId === it.planId);
          const lo = plan?.from && plan.from > minDate ? plan.from : minDate;
          const hi = plan?.to && plan.to < maxDate ? plan.to : maxDate;
          const p = `order-modify-add-item-${idx}`;
          return (
            <div key={idx} className="surface-block stack" data-testid={p}>
              <div className="btn-row">
                <div style={{ flex: '1 1 9rem', minWidth: 0 }}>
                  <label className="field-label" htmlFor={`${p}-date`}>
                    日期
                  </label>
                  <input
                    id={`${p}-date`}
                    type="date"
                    className="form-input"
                    min={lo}
                    max={hi}
                    value={it.date}
                    disabled={disabled}
                    onChange={(e) => updateItem(idx, { date: e.target.value })}
                    data-testid={`${p}-date`}
                  />
                </div>
                <div style={{ flex: '1 1 6rem', minWidth: 0 }}>
                  <label className="field-label" htmlFor={`${p}-count`}>
                    每日次數
                  </label>
                  <select
                    id={`${p}-count`}
                    className="form-input"
                    value={it.dailyCount}
                    disabled={disabled}
                    onChange={(e) => updateItem(idx, { dailyCount: Number(e.target.value) })}
                    data-testid={`${p}-daily-count`}
                  >
                    {[1, 2, 3].map((n) => (
                      <option key={n} value={n}>
                        {n}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <div>
                <label className="field-label" htmlFor={`${p}-plan`}>
                  方案
                </label>
                <select
                  id={`${p}-plan`}
                  className="form-input"
                  value={it.planId}
                  disabled={disabled}
                  onChange={(e) => updateItem(idx, { planId: e.target.value })}
                  data-testid={`${p}-plan`}
                >
                  {plans.map((pl) => (
                    <option key={pl.planId} value={pl.planId}>
                      {pl.title}
                      {pl.unitPrice !== undefined ? `（${formatMoney(pl.unitPrice)}）` : ''}
                    </option>
                  ))}
                </select>
              </div>
              <div className="btn-row">
                {pets.map((pet) => (
                  <label
                    key={pet.petId}
                    style={{ display: 'flex', gap: '0.25rem', alignItems: 'center' }}
                  >
                    <input
                      type="checkbox"
                      disabled={disabled}
                      checked={it.petIds.includes(pet.petId)}
                      onChange={() =>
                        updateItem(idx, {
                          petIds: it.petIds.includes(pet.petId)
                            ? it.petIds.filter((x) => x !== pet.petId)
                            : [...it.petIds, pet.petId]
                        })
                      }
                      data-testid={`${p}-pet-checkbox-${pet.petId}`}
                    />
                    {pet.name}
                  </label>
                ))}
              </div>
              {it.petIds.length === 0 && <p className="field-error">請至少選擇 1 隻毛孩</p>}
              <div className="btn-row">
                <Button
                  variant="secondary"
                  disabled={disabled}
                  style={{ padding: '0.375rem 0.75rem' }}
                  onClick={() =>
                    onChange({ ...draft, addItems: draft.addItems.filter((_, i) => i !== idx) })
                  }
                >
                  移除此列
                </Button>
              </div>
            </div>
          );
        })}
        <div className="btn-row">
          <Button
            variant="secondary"
            disabled={disabled || plans.length === 0}
            onClick={addItem}
            data-testid="order-modify-btn-add-item"
          >
            新增一列行程
          </Button>
        </div>
      </div>
    </div>
  );
};

export default ModificationEditor;
