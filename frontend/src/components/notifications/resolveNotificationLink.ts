import { isSafeRedirect } from '../../utils/safeRedirect';

/**
 * 通知 linkUrl → 前端路由（SD-014 §前端點擊行為；SD-FRONTEND-SPEC §2.5 由 API 回應決定的導頁目標）。
 *
 * 後端只寫入 routes.tsx 存在的路徑（含 SD-ORDER 的 `/orders/{id}/modifications/{mid}`、SD-FULFILL 新增的
 * `/sitter/orders/{id}/visits/{vid}` 與 `/orders/{id}/visits/{vid}/care-log`），這裡原樣導覽。
 *
 * 舊通知相容（資料不做 migration）：
 * - /client/orders[/...] → /owner/orders[/...]
 * - /visits/report?orderId={id} → /owner/orders/{id}
 * - /sitter/eval/{id}、/admin/resolve/{id}、/visit-reports/* 由 routes.tsx 轉址 / 提示頁處理
 *
 * 一律經 safeRedirect 判斷（擋 `//`、`/\`、控制字元、跨來源）；不合格回傳 null（不導覽）。
 */
export const resolveNotificationLink = (linkUrl: string | null | undefined): string | null => {
  if (!isSafeRedirect(linkUrl)) return null;

  if (linkUrl === '/client/orders' || linkUrl.startsWith('/client/orders/')) {
    return linkUrl.replace(/^\/client\/orders/, '/owner/orders');
  }

  const legacyReport = /^\/visits\/report\?orderId=([^&]+)$/.exec(linkUrl);
  if (legacyReport) return `/owner/orders/${legacyReport[1]}`;

  return linkUrl;
};
