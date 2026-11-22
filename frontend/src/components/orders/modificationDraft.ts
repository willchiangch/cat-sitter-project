import type {
  ModificationAddItem,
  OrderDetail,
  QuoteModificationRequest
} from '../../api/orderApi';
import { formatMoney } from '../../utils/money';
import { stripText } from '../../utils/text';

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

export const diffText = (diff: number | undefined): string => {
  if (diff === undefined) return '—';
  if (diff > 0) return `應補款 ${formatMoney(diff)}`;
  if (diff < 0) return `應退款 ${formatMoney(-diff)}`;
  return '無差額';
};

export const isDraftValid = (draft: ModificationDraft) =>
  (draft.removeVisitIds.length > 0 || draft.addItems.length > 0) &&
  draft.addItems.every((i) => !!i.date && !!i.planId && i.petIds.length > 0 && i.dailyCount >= 1);

/** 保母加減欄位（E17 SITTER / E20 / E32 / E33 共用）：只有 CUSTOM_ALLOWED 且任一 > 0 才帶 */
export interface AdjustmentDraft {
  surcharge: string;
  discount: string;
  reason: string;
}

export const adjustmentBody = (
  adj: AdjustmentDraft,
  customAllowed: boolean
): Pick<QuoteModificationRequest, 'surchargeAmount' | 'discountAmount' | 'adjustmentReason'> => {
  const s = adj.surcharge === '' ? 0 : Number(adj.surcharge);
  const d = adj.discount === '' ? 0 : Number(adj.discount);
  if (!customAllowed || (s <= 0 && d <= 0)) return {};
  return {
    ...(s > 0 ? { surchargeAmount: s } : {}),
    ...(d > 0 ? { discountAmount: d } : {}),
    adjustmentReason: stripText(adj.reason)
  };
};
