import type { ModificationAddItem, OrderDetail } from '../../api/orderApi';
import { formatMoney } from '../../utils/money';

export interface ModificationDraft {
  removeVisitIds: string[];
  addItems: ModificationAddItem[];
}

/** 本訂單的方案（E2 groups 依 planId 去重）與毛孩（各群組毛孩聯集，排除已刪除） */
export const orderPlansAndPets = (detail: OrderDetail) => {
  const plans = new Map<
    string,
    { planId: string; title: string; unitPrice?: number; from?: string; to?: string }
  >();
  const pets = new Map<string, { petId: string; name: string }>();
  for (const g of detail.groups ?? []) {
    if (!plans.has(g.planId))
      plans.set(g.planId, {
        planId: g.planId,
        title: g.planTitle,
        unitPrice: g.unitPrice,
        from: g.bookableFrom,
        to: g.bookableTo
      });
    for (const p of g.pets) if (!p.deleted) pets.set(p.petId, { petId: p.petId, name: p.name });
  }
  return { plans: [...plans.values()], pets: [...pets.values()] };
};

/**
 * 新總額系統試算（PRD-016 §4.3-3：Σ 新內容中每個有效行程的快照單價）。前端只作顯示用，送出後以後端
 * `systemAmount` / `newTotalAmount` 為準。有效行程 = 非 CANCELLED 且未被勾選減少。
 */
export const estimateSystemAmount = (
  detail: OrderDetail,
  draft: ModificationDraft,
  cancelAll: boolean
) => {
  const price = new Map((detail.groups ?? []).map((g) => [g.planId, g.unitPrice ?? 0]));
  const remaining = (detail.visits ?? []).filter(
    (v) =>
      v.status !== 'CANCELLED' &&
      !(cancelAll ? v.status === 'PENDING' : draft.removeVisitIds.includes(v.visitId))
  );
  const kept = remaining.reduce((sum, v) => sum + (price.get(v.planId) ?? 0), 0);
  const added = cancelAll
    ? 0
    : draft.addItems.reduce((sum, i) => sum + (price.get(i.planId) ?? 0) * i.dailyCount, 0);
  return kept + added;
};

export const diffText = (diff: number | undefined): string => {
  if (diff === undefined) return '—';
  if (diff > 0) return `應補款 ${formatMoney(diff)}`;
  if (diff < 0) return `應退款 ${formatMoney(-diff)}`;
  return '無差額';
};

export const isDraftValid = (draft: ModificationDraft) =>
  (draft.removeVisitIds.length > 0 || draft.addItems.length > 0) &&
  draft.addItems.every((i) => !!i.date && !!i.planId && i.petIds.length > 0 && i.dailyCount >= 1);
