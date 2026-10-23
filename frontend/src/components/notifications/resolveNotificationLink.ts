/**
 * 通知 linkUrl → 前端路由 (SD-014 §前端點擊行為，Backlog 第 3 批 D1)。
 *
 * 後端現在只會寫入 routes.tsx 真的存在的路徑（NotificationListenerLinkTest 把關），這裡原樣導覽，
 * 不再像過去只認得 /sitter/orders、/client/orders、/sitter/kyc 三個寫死字串（推薦通知的
 * /booking/{id}、影像到期提醒點了都沒反應）。
 *
 * 舊通知相容：2026-10 前寫入 DB 的通知仍是不存在的路徑，資料不做 migration，於此轉換：
 * - /client/orders[/...] → /owner/orders[/...]
 * - /visits/report?orderId={id} → /owner/orders/{id}
 *
 * 只接受同源相對路徑（單一 / 開頭），避免 linkUrl 變成開放重導向；其他值回傳 null（不導覽）。
 */
export const resolveNotificationLink = (linkUrl: string | null | undefined): string | null => {
  if (!linkUrl || !/^\/(?!\/)/.test(linkUrl)) return null;

  if (linkUrl === '/client/orders' || linkUrl.startsWith('/client/orders/')) {
    return linkUrl.replace(/^\/client\/orders/, '/owner/orders');
  }

  const legacyReport = /^\/visits\/report\?orderId=([^&]+)$/.exec(linkUrl);
  if (legacyReport) return `/owner/orders/${legacyReport[1]}`;

  return linkUrl;
};
